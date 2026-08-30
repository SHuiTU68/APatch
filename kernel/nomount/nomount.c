#include <linux/init.h>
#include <linux/fs.h>
#include <linux/file.h>
#include <linux/namei.h>
#include <linux/slab.h>
#include <linux/cred.h>
#include <linux/xattr.h>
#include <linux/module.h>
#include <linux/seq_file.h>
#include <linux/uio.h>
#include <linux/statfs.h>
#include <linux/sched.h>
#include <linux/ctype.h>
#include <linux/pid_namespace.h>
#include "nomount.h"

static struct kmem_cache *nm_dir_cachep __read_mostly, *nm_inode_cachep __read_mostly;
static struct kmem_cache *nm_iop_cachep __read_mostly, *nm_fop_cachep __read_mostly;
static DEFINE_STATIC_KEY_FALSE(nomount_active_uids);

/*** Helpers ***/

static __always_inline bool nomount_is_uid_blocked(uid_t uid)
{
    bool is_blocked;
    if (!static_branch_unlikely(&nomount_active_uids)) return false;
    rcu_read_lock();
    is_blocked = (idr_find(&nomount_uid_idr, uid) != NULL);
    rcu_read_unlock();
    return is_blocked;
}

#define __get_nm(ptr, type, member, field, hook_func) ({ \
    typeof(ptr) __p = (ptr); \
    (likely(__p) && __p->field == (hook_func)) ? container_of(__p, type, member) : NULL; \
})

static __always_inline struct nomount_dir_node *nomount_get_dir_node(struct inode *inode) 
{
    struct nm_iop *nm_iop;
    struct nm_fop *nm_fop;

    nm_iop = __get_nm(smp_load_acquire(&inode->i_op), struct nm_iop, fake_iop, lookup, nomount_hijacked_lookup);
    if (nm_iop && nm_iop->dir_node) return nm_iop->dir_node;

    nm_fop = __get_nm(smp_load_acquire(&inode->i_fop), struct nm_fop, fake_fop, iterate_shared, nomount_hijacked_iterate_dir);
    if (nm_fop && nm_fop->dir_node) return nm_fop->dir_node;
    
    return NULL;
}

static __always_inline struct nomount_child_node *nomount_bsearch_child(struct nomount_child_array *arr, const char *name, size_t len, u32 hash)
{
    int l = 0, n = arr->count;
    u32 *hashes = arr->hashes;

    if (unlikely(n <= 0)) return NULL;
    while (n > 0) {
        int step = n >> 1, m = l + step, less = (hashes[m] < hash);
        l = less ? m + 1 : l;
        n = less ? n - step - 1 : step;
    }
    while (l < arr->count && hashes[l] == hash) {
        struct nomount_child_node *c = arr->nodes[l];
        if (c->name_len == len && !memcmp(c->name, name, len)) 
            return c;
        l++;
    }
    return NULL;
}

static __always_inline bool __nomount_get_rule_info(struct nomount_dir_node *dir_node, const char *name, size_t len, u32 hash, struct nm_rule_info *rule_info, bool get_path)
{
    struct nomount_child_array *arr;
    struct nomount_child_node *c;
    unsigned int seq;
    bool found = false;

    do {
        seq = read_seqcount_begin(&dir_node->seq);
        arr = rcu_dereference(dir_node->children);
        if (likely(arr)) {
            c = nomount_bsearch_child(arr, name, len, hash);
            if (c && c->rule && (c->rule->target_uid == 0 || c->rule->target_uid == current_uid().val)) {
                if (rule_info) {
                    rule_info->flags = c->rule->flags;
                    rule_info->v_ino = c->rule->v_ino;
                    rule_info->this_dir = c->rule->this_dir;
                    (get_path && c->rule->r_path.dentry) ? (void)(rule_info->r_path = c->rule->r_path) : (void)(rule_info->r_path.dentry = NULL);
                }
                found = true;
            } else {
                found = false;
            }
        }
    } while (read_seqcount_retry(&dir_node->seq, seq));

    if (found && rule_info && get_path && rule_info->r_path.dentry) 
        path_get(&rule_info->r_path);

    return found;
}

static __always_inline bool nomount_get_rule_info(struct nomount_dir_node *dir_node, const char *name, size_t len, u32 hash, struct nm_rule_info *rule_info, bool get_path)
{
    bool found;
    if (unlikely(!dir_node)) return false;
    rcu_read_lock();
    found = __nomount_get_rule_info(dir_node, name, len, hash, rule_info, get_path);
    rcu_read_unlock();
    return found;
}

#define NM_DEFINE_RCU_FREE(_name, _type, _cache, ...) \
static void _name(struct rcu_head *head) { \
    _type *obj = container_of(head, _type, rcu); \
    __VA_ARGS__ \
    kmem_cache_free(_cache, obj); \
}
NM_DEFINE_RCU_FREE(nm_iop_rcu_free, struct nm_iop, nm_iop_cachep)
NM_DEFINE_RCU_FREE(nm_fop_rcu_free, struct nm_fop, nm_fop_cachep)

static void nm_dir_rcu_free(struct rcu_head *head)
{
    struct nomount_dir_node *dir = container_of(head, struct nomount_dir_node, rcu);
    struct nomount_child_array *arr = dir->children;
    if (arr) {
        int i; for (i = 0; i < arr->count; i++) kfree(arr->nodes[i]);
        kfree(arr);
    }
    kmem_cache_free(nm_dir_cachep, dir);
}

static inline void nm_destroy_virtual_inode(struct inode *inode)
{
    struct nm_inode_info *info = inode->i_private;
    if (!info) return;
    if (info->r_path.dentry) path_put(&info->r_path);

    if (info->dir_node) {
        WRITE_ONCE(info->dir_node->v_inode, NULL);
        if (READ_ONCE(info->dir_node->_tag_ptr) == 1UL)
            call_rcu(&info->dir_node->rcu, nm_dir_rcu_free);
    }

    kmem_cache_free(nm_inode_cachep, info);
    inode->i_private = NULL;
}

static inline void nm_destroy_hijacked_inode(struct inode *inode, bool restore)
{
    struct nm_iop *nm_iop = __get_nm(inode->i_op, struct nm_iop, fake_iop, lookup, nomount_hijacked_lookup);
    struct nm_fop *nm_fop = __get_nm(inode->i_fop, struct nm_fop, fake_fop, iterate_shared, nomount_hijacked_iterate_dir);
    struct nomount_dir_node *dir_node = nm_iop ? nm_iop->dir_node : (nm_fop ? nm_fop->dir_node : NULL);

    if (nm_iop) {
        if (dir_node) RCU_INIT_POINTER(dir_node->iop, NULL);
        if (restore) smp_store_release(&inode->i_op, nm_iop->orig_iop);
        call_rcu(&nm_iop->rcu, nm_iop_rcu_free);
    }
    if (nm_fop) {
        if (dir_node) RCU_INIT_POINTER(dir_node->fop, NULL);
        if (restore) smp_store_release(&inode->i_fop, nm_fop->orig_fop);
        call_rcu(&nm_fop->rcu, nm_fop_rcu_free);
    }
    if (dir_node && !(dir_node->_tag_ptr & 1UL)) {
        smp_mb();
        if (!rcu_access_pointer(dir_node->children) &&
             cmpxchg(&dir_node->v_inode, NULL, (struct inode *)-1L) == NULL)
                call_rcu(&dir_node->rcu, nm_dir_rcu_free);
    }
}

struct nomount_proxy_ctx {
    struct dir_context ctx;
    struct dir_context *orig_ctx;
    struct nomount_dir_node *dir_node;
    int emitted;
};

static NM_ACTOR_RET nomount_actor_proxy(struct dir_context *ctx, const char *name, int namelen,
                                        loff_t offset, u64 ino, unsigned int d_type)
{
    struct nomount_proxy_ctx *proxy = container_of(ctx, struct nomount_proxy_ctx, ctx);
    NM_ACTOR_RET ret;
    bool is_injected = false;

    if (proxy->dir_node) {
        u32 hash = full_name_hash((const void *)(unsigned long)NOMOUNT_MAGIC_SIG, name, namelen);
        if (proxy->dir_node->bloom_mask & (1ULL << (hash & 63))) {
            unsigned int seq;
            uid_t fsuid = current_uid().val;
            rcu_read_lock();
            do {
                struct nomount_child_array *arr;
                struct nomount_child_node *c;
                seq = read_seqcount_begin(&proxy->dir_node->seq);
                arr = rcu_dereference(proxy->dir_node->children);
                is_injected = likely(arr) && (c = nomount_bsearch_child(arr, name, namelen, hash)) &&
                                              c->rule && (!c->rule->target_uid || c->rule->target_uid == fsuid);
            } while (read_seqcount_retry(&proxy->dir_node->seq, seq));
            rcu_read_unlock();
        }
    }

    if (is_injected) {
        proxy->ctx.pos = offset;
        return NM_ACTOR_CONTINUE;
    }

    proxy->orig_ctx->pos = proxy->ctx.pos;
    ret = proxy->orig_ctx->actor(proxy->orig_ctx, name, namelen, offset, ino, d_type);
    proxy->ctx.pos = proxy->orig_ctx->pos;
    proxy->emitted++;

    return ret;
}

static inline void nomount_emit_virtual_children(struct dir_context *ctx, struct nomount_dir_node *dir_node)
{
	struct nomount_child_array *array;
	int id, srcu_idx;

	if (!dir_node) return;
	if (!nm_is_virtual_pos(ctx->pos)) ctx->pos = nm_pack_pos(0);
	srcu_idx = srcu_read_lock(&nomount_srcu);
	array = srcu_dereference(dir_node->children, &nomount_srcu);
	if (array) {
		for (id = nm_unpack_pos(ctx->pos); id < array->count; id++) {
			struct nomount_child_node *child;
			ctx->pos = nm_pack_pos(id);
			if ((child = array->nodes[id]) && (child->rule->target_uid == 0 || child->rule->target_uid == current_uid().val)) {
				if (!(child->flags & NM_FLAG_WHITEOUT) && !dir_emit(ctx, child->name, child->name_len, child->fake_ino, child->d_type)) break;
			}
			ctx->pos = nm_pack_pos(id + 1);
		}
	}
	srcu_read_unlock(&nomount_srcu, srcu_idx);
}

static void nomount_init_prealloc_inode(struct inode *inode, struct nm_inode_info *info, struct nm_rule_info *rule_info)
{
    info->flags = rule_info->flags;
    info->dir_node = rule_info->this_dir;
    info->r_path = (!(rule_info->flags & NM_FLAG_VIRTUAL_DIR) && rule_info->r_path.dentry) ? rule_info->r_path : (struct path){ .mnt = NULL, .dentry = NULL };
    info->v_ino = inode->i_ino = rule_info->v_ino;
    inode->i_private = info;

    struct inode *r_inode = info->r_path.dentry ? d_backing_inode(info->r_path.dentry) : NULL;
    inode->i_mode   = r_inode ? r_inode->i_mode    : (S_IFDIR | 0755);
    inode->i_size   = r_inode ? i_size_read(r_inode) : 4096;
    inode->i_blocks = r_inode ? r_inode->i_blocks  : 8;
    inode->i_uid    = r_inode ? r_inode->i_uid     : GLOBAL_ROOT_UID;
    inode->i_gid    = r_inode ? r_inode->i_gid     : GLOBAL_ROOT_GID;
    inode->i_op     = (r_inode && !S_ISDIR(r_inode->i_mode)) ? &nm_file_iops : &nm_dir_iops;

    if (r_inode && !S_ISDIR(r_inode->i_mode)) {
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 16, 0)
        inode->i_fop = (r_inode->i_fop && r_inode->i_fop->mmap_prepare) ? &nm_file_fops_mmap_prepare : &nm_file_fops;
#else
        inode->i_fop = &nm_file_fops;
#endif
    } else {
        inode->i_fop = &nm_dir_fops;
    }

    if (r_inode) nm_sync_inode_times(inode, r_inode), inode->i_mapping = r_inode->i_mapping;
    inode->i_flags |= S_PRIVATE | S_NOATIME | S_NOCMTIME | S_NOSEC;
    if (!S_ISLNK(inode->i_mode)) inode->i_opflags |= IOP_NOFOLLOW;
}

static struct dentry *nomount_resolve_rule_dentry(struct inode *dir, struct dentry *dentry, struct nomount_dir_node *dir_node, u32 hash)
{
    struct nm_inode_info *prealloc_info = NULL;
    struct inode *splice_inode = NULL, *prealloc_inode = NULL;
    struct dentry *res = ERR_PTR(-ENODATA);
    struct nm_rule_info rule_info = {0};

    if (!nomount_get_rule_info(dir_node, dentry->d_name.name, dentry->d_name.len, hash, NULL, false)) 
        return res;

    if (likely((prealloc_inode = new_inode(dir->i_sb)))) {
        if (unlikely(!(prealloc_info = kmem_cache_alloc(nm_inode_cachep, GFP_KERNEL)))) {
            iput(prealloc_inode);
            prealloc_inode = NULL;
        }
    }

    rcu_read_lock();
    if (unlikely(!__nomount_get_rule_info(dir_node, dentry->d_name.name, dentry->d_name.len, hash, &rule_info, true)))
        goto unlock_out;

    if (unlikely(nomount_is_uid_blocked(current_uid().val))) {
        if (d_is_negative(dentry)) d_drop(dentry);
        goto unlock_out;
    }

    if (rule_info.flags & NM_FLAG_WHITEOUT) {
        nomount_hijack_dentry_ops(dentry);
        d_add(dentry, NULL); res = NULL;
        goto unlock_out;
    }

    if (likely(prealloc_inode && ((rule_info.flags & NM_FLAG_VIRTUAL_DIR) || rule_info.r_path.dentry))) {
        if (rule_info.this_dir && (splice_inode = cmpxchg(&rule_info.this_dir->v_inode, NULL, prealloc_inode))) {
            if (splice_inode == (struct inode *)-1L) goto unlock_out;
            igrab(splice_inode);
        } else {
            nomount_init_prealloc_inode(prealloc_inode, prealloc_info, &rule_info);
            splice_inode = prealloc_inode;
            prealloc_inode = NULL; prealloc_info = NULL;
            rule_info.r_path.dentry = NULL; 
        }

        rcu_read_unlock();
        if (!IS_ERR((res = d_splice_alias(splice_inode, dentry))))
            nomount_hijack_dentry_ops(res ? res : dentry);
            
        goto cleanup_out;
    }

unlock_out:
    rcu_read_unlock();
cleanup_out:
    if (rule_info.r_path.dentry) 
        path_put(&rule_info.r_path);

    if (prealloc_inode) {
        kmem_cache_free(nm_inode_cachep, prealloc_info);
        iput(prealloc_inode);
    }    
    return res;
}

/*** i_op / s_op / f_op Hijacking Hooks ***/

static struct dentry *nomount_hijacked_lookup(struct inode *dir, struct dentry *dentry, unsigned int flags)
{
    struct nm_iop *nm_iop = __get_nm(smp_load_acquire(&dir->i_op), struct nm_iop, fake_iop, lookup, nomount_hijacked_lookup);
    struct nomount_dir_node *dir_node = nm_iop ? READ_ONCE(nm_iop->dir_node) : NULL;
    struct dentry *res;
    u32 hash;

    if (unlikely(!nm_iop || !dir_node))
        goto do_real_lookup;

    hash = full_name_hash((const void *)(unsigned long)NOMOUNT_MAGIC_SIG, dentry->d_name.name, dentry->d_name.len);
    if (likely(!(READ_ONCE(dir_node->bloom_mask) & (1ULL << (hash & 63)))))
        goto do_real_lookup;

    if ((res = nomount_resolve_rule_dentry(dir, dentry, dir_node, hash)) != ERR_PTR(-ENODATA))
        return res;

do_real_lookup:
    if (likely(nm_iop && nm_iop->orig_iop && nm_iop->orig_iop->lookup)) {
        return nm_iop->orig_iop->lookup(dir, dentry, flags);
    }
    return ERR_PTR(-EOPNOTSUPP);
}

static int nomount_hijacked_iterate_dir(struct file *file, struct dir_context *ctx)
{
    struct nm_fop *nm_fop = __get_nm(smp_load_acquire(&file->f_op), struct nm_fop, fake_fop, iterate_shared, nomount_hijacked_iterate_dir);
    struct nomount_dir_node *dir_node = nm_fop ? READ_ONCE(nm_fop->dir_node) : NULL;
    const struct file_operations *orig_fop = nm_fop ? nm_fop->orig_fop : NULL;
    struct nomount_proxy_ctx proxy_ctx = { .ctx.actor = nomount_actor_proxy };
    int res = 0;

    if (unlikely(!orig_fop || !dir_node))
        goto do_real_iterate;

    if (unlikely(nm_is_virtual_pos(ctx->pos))) {
        if (likely(!nomount_is_uid_blocked(current_uid().val)))
            nomount_emit_virtual_children(ctx, dir_node);
        return 0;
    }

    if (unlikely(nomount_is_uid_blocked(current_uid().val) || !READ_ONCE(dir_node->bloom_mask)))
        goto do_real_iterate;

    proxy_ctx.ctx.pos = ctx->pos;
    proxy_ctx.orig_ctx = ctx;
    proxy_ctx.dir_node = dir_node;
    proxy_ctx.emitted = 0;

    res = nm_call_iterate(file, &proxy_ctx.ctx, orig_fop);
    ctx->pos = proxy_ctx.ctx.pos;
    
    if (res < 0 || proxy_ctx.emitted > 0) 
        return res;

    ctx->pos = nm_pack_pos(0);
    nomount_emit_virtual_children(ctx, dir_node);
    return res;

do_real_iterate:
    if (likely(orig_fop)) 
        return nm_call_iterate(file, ctx, orig_fop);
    return -ENOTDIR;
}

static void nomount_hijacked_destroy_inode(struct inode *inode)
{
    struct nm_sop *nm_sop;
    (inode->i_op == &nm_file_iops || inode->i_op == &nm_dir_iops) ? nm_destroy_virtual_inode(inode) : nm_destroy_hijacked_inode(inode, false);

    nm_sop = __get_nm(smp_load_acquire(&inode->i_sb->s_op), struct nm_sop, fake_sop, destroy_inode, nomount_hijacked_destroy_inode);
    if (nm_sop && nm_sop->orig_sop && nm_sop->orig_sop->destroy_inode)
        nm_sop->orig_sop->destroy_inode(inode);
}

static int nomount_hijacked_drop_inode(struct inode *inode)
{
    struct nm_sop *nm_sop;
    if (inode->i_op == &nm_file_iops || inode->i_op == &nm_dir_iops) goto generic_fn;

    nm_sop = __get_nm(smp_load_acquire(&inode->i_sb->s_op), struct nm_sop, fake_sop, drop_inode, nomount_hijacked_drop_inode);
    if (nm_sop && nm_sop->orig_sop && nm_sop->orig_sop->drop_inode)
        return nm_sop->orig_sop->drop_inode(inode);

generic_fn:
    return !inode->i_nlink || inode_unhashed(inode);
}

static void nomount_hijacked_evict_inode(struct inode *inode)
{
    struct nm_sop *nm_sop;
    if (inode->i_op == &nm_file_iops || inode->i_op == &nm_dir_iops) goto generic_fn;

    nm_sop = __get_nm(smp_load_acquire(&inode->i_sb->s_op), struct nm_sop, fake_sop, evict_inode, nomount_hijacked_evict_inode);
    if (nm_sop && nm_sop->orig_sop && nm_sop->orig_sop->evict_inode) {
        nm_sop->orig_sop->evict_inode(inode);
    } else {
generic_fn:
        truncate_inode_pages_final(&inode->i_data);
        clear_inode(inode);
    }
}

static int nomount_hijacked_statfs(struct dentry *dentry, struct kstatfs *buf)
{
    struct nm_sop *nm_sop = __get_nm(smp_load_acquire(&dentry->d_sb->s_op), struct nm_sop, fake_sop, statfs, nomount_hijacked_statfs);
    int res;

    if (nm_sop && nm_sop->orig_sop && nm_sop->orig_sop->statfs)
        res = nm_sop->orig_sop->statfs(dentry, buf);
    else
        res = simple_statfs(dentry, buf);

    /* NM_HIDE_STATFS: spoof f_type only when a hide rule set one for this sb
     * (gated by a static branch so the no-hide-rule fast path costs nothing). */
    if (res == 0 && static_branch_unlikely(&nomount_hide_statfs_active) &&
        nm_sop && READ_ONCE(nm_sop->fake_f_type))
        buf->f_type = READ_ONCE(nm_sop->fake_f_type);

    return res;
}

/*** file / inode / superblock operations ***/

static int nm_open(struct inode *inode, struct file *file)
{
    struct nm_inode_info *info = inode->i_private;
    struct file *real_file;

    if (unlikely(!info)) return -ENODEV;
    if (unlikely(info->flags & NM_FLAG_VIRTUAL_DIR)) {
        file->private_data = NULL;
        return 0;
    }
    if (unlikely(!info->r_path.dentry)) return -ENODEV;

    real_file = dentry_open(&info->r_path, file->f_flags, file->f_cred);
    if (IS_ERR(real_file)) return PTR_ERR(real_file);

    file->private_data = real_file;
    return 0;
}

static int nm_release(struct inode *inode, struct file *file)
{
    struct file *real_file = file->private_data;
    if (real_file) fput(real_file), file->private_data = NULL;
    return 0;
}

static loff_t nm_llseek(struct file *file, loff_t offset, int whence)
{
    struct file *real_file = file->private_data;
    loff_t res;
    if (!real_file) return -EINVAL;

    real_file->f_pos = file->f_pos;
    res = vfs_llseek(real_file, offset, whence);
    file->f_pos = real_file->f_pos;

    return res;
}

static ssize_t nm_read_iter(struct kiocb *iocb, struct iov_iter *to)
{
    struct file *file = iocb->ki_filp;
    struct file *real_file = file->private_data;
    ssize_t ret;
    if (!real_file || !real_file->f_op->read_iter) return -EINVAL;

    iocb->ki_filp = real_file;
    ret = real_file->f_op->read_iter(iocb, to);
    iocb->ki_filp = file;

    return ret;
}

static ssize_t nm_write_iter(struct kiocb *iocb, struct iov_iter *from)
{
    struct file *file = iocb->ki_filp;
    struct file *real_file = file->private_data;
    ssize_t ret;
    if (!real_file || !real_file->f_op->write_iter) return -EINVAL;

    iocb->ki_filp = real_file;
    ret = real_file->f_op->write_iter(iocb, from);
    iocb->ki_filp = file;

    return ret;
}

static int nm_mmap(struct file *file, struct vm_area_struct *vma)
{
    int ret = generic_file_mmap(file, vma);
    return ret ? ret : (file_inode(file)->i_flags &= ~S_PRIVATE, 0);
}

#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 16, 0)
static int nm_mmap_prepare(struct vm_area_desc *desc)
{
    int ret = generic_file_mmap_prepare(desc);
    return ret ? ret : (file_inode(desc->file)->i_flags &= ~S_PRIVATE, 0);
}
#endif

static long nm_unlocked_ioctl(struct file *file, unsigned int cmd, unsigned long arg)
{
    struct file *real_file = file->private_data;
    if (!real_file || !real_file->f_op->unlocked_ioctl) return -ENOTTY;
    return real_file->f_op->unlocked_ioctl(real_file, cmd, arg);
}

#ifdef CONFIG_COMPAT
static long nm_compat_ioctl(struct file *file, unsigned int cmd, unsigned long arg)
{
    struct file *real_file = file->private_data;
    if (!real_file || !real_file->f_op->compat_ioctl) return -ENOTTY;
    return real_file->f_op->compat_ioctl(real_file, cmd, arg);
}
#endif

static ssize_t nm_splice_read(struct file *in, loff_t *ppos, struct pipe_inode_info *pipe,
                              size_t len, unsigned int flags)
{
    struct file *real_file = in->private_data;
    if (!real_file || !real_file->f_op->splice_read) return -EINVAL;
    return real_file->f_op->splice_read(real_file, ppos, pipe, len, flags);
}

static ssize_t nm_splice_write(struct pipe_inode_info *pipe, struct file *out,
                               loff_t *ppos, size_t len, unsigned int flags)
{
    struct file *real_file = out->private_data;
    if (!real_file || !real_file->f_op->splice_write) return -EINVAL;
    return real_file->f_op->splice_write(pipe, real_file, ppos, len, flags);
}

static int nm_fsync(struct file *file, loff_t start, loff_t end, int datasync)
{
    struct file *real_file = file->private_data;
    if (!real_file || !real_file->f_op->fsync) return -EINVAL;
    return real_file->f_op->fsync(real_file, start, end, datasync);
}

static ssize_t nm_listxattr(struct dentry *dentry, char *buffer, size_t size)
{
    struct nm_inode_info *info = d_backing_inode(dentry)->i_private;
    if (unlikely(!info || (info->flags & NM_FLAG_VIRTUAL_DIR) || !d_backing_inode(info->r_path.dentry)->i_op->listxattr))
        return -EOPNOTSUPP;

    return d_backing_inode(info->r_path.dentry)->i_op->listxattr(info->r_path.dentry, buffer, size);
}

#if LINUX_VERSION_CODE < KERNEL_VERSION(4, 11, 0)
static int nm_file_getattr(struct vfsmount *mnt, struct dentry *dentry, struct kstat *stat)
#else
static int nm_file_getattr(IDMAP_ARG const struct path *path, struct kstat *stat, u32 request_mask, unsigned int query_flags)
#endif
{
#if LINUX_VERSION_CODE >= KERNEL_VERSION(4, 11, 0)
    struct dentry *dentry = path->dentry;
#endif
    struct inode *v_inode = d_backing_inode(dentry);
    struct nm_inode_info *info = v_inode->i_private;
    int res;
    if (unlikely(!info)) return -EIO;

    if (unlikely(info->flags & NM_FLAG_VIRTUAL_DIR)) {
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 3, 0)
        generic_fillattr(IDMAP_CALL request_mask, v_inode, stat);
#else
        generic_fillattr(IDMAP_CALL v_inode, stat);
#endif
        stat->ino = info->v_ino;
        stat->dev = v_inode->i_sb->s_dev;
        return 0;
    }

#if LINUX_VERSION_CODE < KERNEL_VERSION(4, 11, 0)
    res = vfs_getattr_nosec(&info->r_path, stat);
#else
    res = vfs_getattr_nosec(&info->r_path, stat, request_mask, query_flags);
#endif
    if (likely(res == 0)) {
        stat->ino = info->v_ino;
        stat->dev = v_inode->i_sb->s_dev;
    }
    return res;
}

static int nm_setattr(IDMAP_ARG struct dentry *dentry, struct iattr *attr)
{
    struct inode *v_inode = d_inode(dentry);
    struct nm_inode_info *info = v_inode->i_private;
    int err;

    if (unlikely(!info)) return -EIO;
    if (info->flags & NM_FLAG_VIRTUAL_DIR) return 0;

    inode_lock(d_backing_inode(info->r_path.dentry));
    err = notify_change(IDMAP_CALL info->r_path.dentry, attr, NULL);
    inode_unlock(d_backing_inode(info->r_path.dentry));

    if (likely(!err)) {
        if (attr->ia_valid & ATTR_MODE) v_inode->i_mode = d_backing_inode(info->r_path.dentry)->i_mode;
        if (attr->ia_valid & ATTR_UID)  v_inode->i_uid = d_backing_inode(info->r_path.dentry)->i_uid;
        if (attr->ia_valid & ATTR_GID)  v_inode->i_gid = d_backing_inode(info->r_path.dentry)->i_gid;
        nm_sync_inode_times(v_inode, d_backing_inode(info->r_path.dentry));
    }
    return err;
}

static const char *nm_get_link(struct dentry *dentry, struct inode *inode, struct delayed_call *done)
{
    struct nm_inode_info *info = inode->i_private;
    struct inode *real_inode;
    struct dentry *target_dentry;
    if (unlikely(!info || !info->r_path.dentry)) return ERR_PTR(-ECHILD);

    real_inode = d_backing_inode(info->r_path.dentry);
    target_dentry = dentry ? info->r_path.dentry : NULL;
    if (real_inode && real_inode->i_op && real_inode->i_op->get_link) {
        return real_inode->i_op->get_link(target_dentry, real_inode, done);
    }

    return ERR_PTR(-EINVAL);
}

static int nm_dir_iterate_dir(struct file *file, struct dir_context *ctx)
{
    struct nm_inode_info *info = file_inode(file)->i_private;
    struct nomount_dir_node *dir_node = info ? info->dir_node : NULL;
    struct file *real_file = file->private_data;
    int res = 0;
    if (unlikely(nm_is_virtual_pos(ctx->pos))) goto emit_virtual;

    if (real_file) {
        struct nomount_proxy_ctx proxy_ctx = {
            .ctx.actor = nomount_actor_proxy, .ctx.pos = ctx->pos,
            .orig_ctx = ctx, .dir_node = dir_node, .emitted = 0
        };
        res = nm_call_iterate(real_file, &proxy_ctx.ctx, real_file->f_op);
        ctx->pos = proxy_ctx.ctx.pos;
        if (res < 0 || proxy_ctx.emitted > 0) return res;
        ctx->pos = nm_pack_pos(0);
    } else if (info && (info->flags & NM_FLAG_VIRTUAL_DIR)) {
        if (ctx->pos < 2 && !dir_emit_dots(file, ctx)) return 0;
        ctx->pos = nm_pack_pos(0);
    } else {
        return -ENOTDIR;
    }

emit_virtual:
    nomount_emit_virtual_children(ctx, dir_node);
    return res;
}

static struct dentry *nm_dir_lookup(struct inode *dir, struct dentry *dentry, unsigned int flags)
{
    struct nm_inode_info *info = dir->i_private; 
    struct dentry *res;

    if (info->dir_node) {
        u32 v_hash = full_name_hash((const void *)(unsigned long)NOMOUNT_MAGIC_SIG, dentry->d_name.name, dentry->d_name.len);
        if (READ_ONCE(info->dir_node->bloom_mask) & (1ULL << (v_hash & 63)) &&
            (res = nomount_resolve_rule_dentry(dir, dentry, info->dir_node, v_hash)) != ERR_PTR(-ENODATA))
                return res;
    }

    if (info->flags & NM_FLAG_VIRTUAL_DIR)
        goto negative_dentry;

    if (info->r_path.dentry) {
        struct inode *r_dir = d_backing_inode(info->r_path.dentry);
        if (r_dir->i_op->lookup)
            return r_dir->i_op->lookup(r_dir, dentry, flags);
    }
    return ERR_PTR(-EOPNOTSUPP);

negative_dentry:
    nomount_hijack_dentry_ops(dentry);
    d_add(dentry, NULL);
    return NULL;
}

struct nm_xattr_proxy {
    struct xattr_handler fake;
    const struct xattr_handler *orig;
};

static int nm_xattr_get(const struct xattr_handler *handler, struct dentry *dentry, struct inode *inode, const char *name, void *buffer, size_t size FLAGS_ARG)
{
    struct nm_xattr_proxy *proxy = container_of(handler, struct nm_xattr_proxy, fake);
    if (inode->i_op == &nm_file_iops || inode->i_op == &nm_dir_iops) {
        struct nm_inode_info *info = inode->i_private;
        if (unlikely(!info || !info->r_path.dentry)) return -ENODATA;
        return __vfs_getxattr(info->r_path.dentry, d_inode(info->r_path.dentry), xattr_full_name(handler, name), buffer, size FLAGS_VAL);
    }

    return proxy->orig->get(proxy->orig, dentry, inode, name, buffer, size FLAGS_VAL);
}

static int nm_xattr_set(const struct xattr_handler *handler, IDMAP_ARG struct dentry *dentry, struct inode *inode, const char *name, const void *buffer, size_t size, int flags)
{
    struct nm_xattr_proxy *proxy = container_of(handler, struct nm_xattr_proxy, fake);
    if (inode->i_op == &nm_file_iops || inode->i_op == &nm_dir_iops) {
        struct nm_inode_info *info = inode->i_private;
        if (unlikely(!info || !info->r_path.dentry)) return -ENODATA;
        return __vfs_setxattr(IDMAP_PATH(info->r_path) info->r_path.dentry, d_inode(info->r_path.dentry), xattr_full_name(handler, name), buffer, size, flags);
    }
    return proxy->orig->set(proxy->orig, IDMAP_CALL dentry, inode, name, buffer, size, flags);
}

#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 13, 0)
static int nm_d_revalidate(struct inode *parent_inode, const struct qstr *name, struct dentry *dentry, unsigned int flags)
#else
static int nm_d_revalidate(struct dentry *dentry, unsigned int flags)
#endif
{
    struct nomount_dir_node *parent_dir;
    struct nm_rule_info rule_info;
    struct inode *inode;
    bool injected;

#if LINUX_VERSION_CODE < KERNEL_VERSION(6, 13, 0)
    struct inode *parent_inode = d_inode(READ_ONCE(dentry->d_parent));
    const struct qstr *name = &dentry->d_name;
#endif
    if (unlikely(!parent_inode)) return 1;

    if (parent_inode->i_op == &nm_dir_iops) {
        parent_dir = ((struct nm_inode_info *)parent_inode->i_private)->dir_node;
    } else {
        struct nm_iop *iop = __get_nm(smp_load_acquire(&parent_inode->i_op), struct nm_iop, fake_iop, lookup, nomount_hijacked_lookup);
        parent_dir = iop ? iop->dir_node : NULL;
    }

    inode = READ_ONCE(dentry->d_inode);
    injected = inode && (inode->i_op == &nm_file_iops || inode->i_op == &nm_dir_iops);

    if (parent_dir) {
        u32 hash = full_name_hash((const void *)(unsigned long)NOMOUNT_MAGIC_SIG, name->name, name->len);
        if (nomount_get_rule_info(parent_dir, name->name, name->len, hash, &rule_info, false) && 
            !nomount_is_uid_blocked(current_uid().val)) {
            if (rule_info.flags & NM_FLAG_WHITEOUT) return !inode;
            return injected;
        }
    }

    if (!inode) {
        if (flags & LOOKUP_RCU) return -ECHILD;
        d_drop(dentry);
        return 0;
    }

    return !injected;
}

#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 16, 0)
static const struct file_operations nm_file_fops_mmap_prepare = {
    .owner = THIS_MODULE,
    .llseek = nm_llseek,
    .open = nm_open,
    .release = nm_release,
    .read_iter = nm_read_iter,
    .write_iter = nm_write_iter,
    .mmap_prepare = nm_mmap_prepare,
    .unlocked_ioctl = nm_unlocked_ioctl,
#ifdef CONFIG_COMPAT
    .compat_ioctl = nm_compat_ioctl,
#endif
    .splice_read = nm_splice_read,
    .splice_write = nm_splice_write,
    .fsync = nm_fsync,
};
#endif

static const struct file_operations nm_file_fops = {
    .owner = THIS_MODULE,
    .llseek = nm_llseek,
    .open = nm_open,
    .release = nm_release,
    .read_iter = nm_read_iter,
    .write_iter = nm_write_iter,
    .mmap = nm_mmap,
    .unlocked_ioctl = nm_unlocked_ioctl,
#ifdef CONFIG_COMPAT
    .compat_ioctl = nm_compat_ioctl,
#endif
    .splice_read = nm_splice_read,
    .splice_write = nm_splice_write,
    .fsync = nm_fsync,
};

static const struct inode_operations nm_file_iops = {
    .getattr = nm_file_getattr,
    .setattr = nm_setattr,
    .listxattr = nm_listxattr,
    .get_link = nm_get_link,
};

static const struct file_operations nm_dir_fops = {
    .owner = THIS_MODULE,
    .open = nm_open,
    .release = nm_release,
    .llseek = nm_llseek,
    .read = generic_read_dir,
    .iterate_shared = nm_dir_iterate_dir,
#if LINUX_VERSION_CODE < KERNEL_VERSION(6, 6, 0)
    .iterate = nm_dir_iterate_dir,
#endif
};

static const struct inode_operations nm_dir_iops = {
    .lookup = nm_dir_lookup,
    .getattr = nm_file_getattr,
    .setattr = nm_setattr,
    .listxattr = nm_listxattr,
};

/* --- Hijacking Management --- */

static inline void nomount_hijack_superblock(struct super_block *sb)
{
    struct nm_sop *nm_sop;
    int count = 0;

    if (unlikely(!sb || !sb->s_op ||
                __get_nm(smp_load_acquire(&sb->s_op), struct nm_sop, fake_sop, destroy_inode, nomount_hijacked_destroy_inode) ||
                !(nm_sop = kzalloc(sizeof(*nm_sop), GFP_KERNEL)))) return;

    nm_sop->fake_sop = *(sb->s_op);
    nm_sop->orig_sop = sb->s_op;
    nm_sop->sb = sb;
    nm_sop->fake_sop.destroy_inode = nomount_hijacked_destroy_inode;
    nm_sop->fake_sop.drop_inode = nomount_hijacked_drop_inode;
    nm_sop->fake_sop.evict_inode = nomount_hijacked_evict_inode;
    nm_sop->fake_sop.statfs = nomount_hijacked_statfs;

    if (sb->s_xattr && !nm_sop->orig_xattr) {
        const struct xattr_handler **new_array;
        struct nm_xattr_proxy *proxies;

        while (sb->s_xattr[count]) count++;
        if ((new_array = kzalloc((count + 1) * sizeof(void *) + (count * sizeof(*proxies)), GFP_KERNEL))) {
            proxies = (void *)(new_array + count + 1);
            for (int i = 0; i < count; i++) {
                proxies[i].orig = sb->s_xattr[i];
                proxies[i].fake = *sb->s_xattr[i];
                if (proxies[i].fake.get) proxies[i].fake.get = nm_xattr_get;
                if (proxies[i].fake.set) proxies[i].fake.set = nm_xattr_set;
                new_array[i] = &proxies[i].fake;
            }
            nm_sop->orig_xattr = (const struct xattr_handler **)sb->s_xattr;
            nm_sop->fake_xattr = new_array;
            smp_store_release((const struct xattr_handler ***)&sb->s_xattr, new_array);
            nm_debug("xattr handlers successfully hijacked for dev: 0x%x\n", sb->s_dev);
        }
    }

    list_add_tail_rcu(&nm_sop->list, &nomount_sb_list);
    smp_store_release(&sb->s_op, &nm_sop->fake_sop);
    nm_debug("Superblock successfully hijacked for dev: 0x%x\n", sb->s_dev);
}

static inline void nomount_hijack_dir_ops(struct nomount_dir_node *dir_node, struct inode *inode)
{
    struct nm_iop *nm_iop = NULL;
    struct nm_fop *nm_fop = NULL;

    if (inode->i_op && !__get_nm(smp_load_acquire(&inode->i_op), struct nm_iop, fake_iop, lookup, nomount_hijacked_lookup)) {
        if (likely((nm_iop = kmem_cache_zalloc(nm_iop_cachep, GFP_KERNEL)))) {
            nm_iop->fake_iop = *(inode->i_op);
            nm_iop->orig_iop = inode->i_op;
            nm_iop->dir_node = dir_node;

            nm_iop->fake_iop.lookup = nomount_hijacked_lookup;
            rcu_assign_pointer(dir_node->iop, nm_iop);
            smp_store_release(&inode->i_op, &nm_iop->fake_iop);
        }
    }

    if (inode->i_fop && !__get_nm(smp_load_acquire(&inode->i_fop), struct nm_fop, fake_fop, iterate_shared, nomount_hijacked_iterate_dir)) {
        if (likely((nm_fop = kmem_cache_zalloc(nm_fop_cachep, GFP_KERNEL)))) {
            nm_fop->fake_fop = *(inode->i_fop);
            nm_fop->orig_fop = inode->i_fop;
            nm_fop->dir_node = dir_node;

            nm_fop->fake_fop.iterate_shared = nomount_hijacked_iterate_dir;
#if LINUX_VERSION_CODE < KERNEL_VERSION(6, 6, 0)
            if (nm_fop->fake_fop.iterate)
                nm_fop->fake_fop.iterate = nomount_hijacked_iterate_dir;
#endif
            rcu_assign_pointer(dir_node->fop, nm_fop);
            smp_store_release(&inode->i_fop, &nm_fop->fake_fop);
        }
    }

    if (nm_iop || nm_fop) nm_debug("Successfully hijacked VFS ops for parent dir (ino: %lu)\n", inode->i_ino);
}

static void nomount_hijack_dentry_ops(struct dentry *dentry)
{
    static const struct dentry_operations nm_dops = { .d_revalidate = nm_d_revalidate };
    if (!dentry) return;
    spin_lock(&dentry->d_lock);
    if (dentry->d_op != &nm_dops) {
        dentry->d_op = &nm_dops;
        dentry->d_flags &= ~(DCACHE_OP_WEAK_REVALIDATE | DCACHE_OP_DELETE | DCACHE_OP_PRUNE
                             | DCACHE_OP_COMPARE | DCACHE_OP_HASH | DCACHE_OP_REAL);
        dentry->d_flags |= DCACHE_OP_REVALIDATE;
    }
    spin_unlock(&dentry->d_lock);
}

static __always_inline void nomount_cure_sb_inodes(struct super_block *sb)
{
    struct inode *inode;
    spin_lock(&sb->s_inode_list_lock);
    list_for_each_entry(inode, &sb->s_inodes, i_sb_list) {
        if (!inode->i_op && !inode->i_fop) continue;
        nm_destroy_hijacked_inode(inode, true);
    }
    spin_unlock(&sb->s_inode_list_lock);
}

static void nomount_restore_superblocks(void)
{
    struct nm_sop *nm_sop, *tmp;
    list_for_each_entry_safe(nm_sop, tmp, &nomount_sb_list, list) {
        if (nm_sop->sb) {
            shrink_dcache_sb(nm_sop->sb);
            nomount_cure_sb_inodes(nm_sop->sb);
            smp_store_release(&nm_sop->sb->s_op, nm_sop->orig_sop);
            if (nm_sop->fake_xattr) {
                smp_store_release((const struct xattr_handler ***)&nm_sop->sb->s_xattr, nm_sop->orig_xattr);
                kfree(nm_sop->fake_xattr); 
            }
            nm_debug("Successfully cured superblock for dev: 0x%x\n", nm_sop->sb->s_dev);
        }
        list_del_rcu(&nm_sop->list);
        kfree_rcu(nm_sop, rcu);
    }
}

/*** Hide subsystem (Kasumi-style, kprobe-free) ***
 *
 * mountinfo/mounts/maps/smaps are all seq_file-backed proc files whose
 * .read == seq_read().  Instead of kprobe/ftrace we:
 *   1. attach a filtering file_operations proxy to the proc inode
 *      (namespace-global /proc/mounts, and lazily per-pid files via
 *       a proc-root lookup hook that hijacks pid-dir i_op),
 *   2. on first read, drain the whole seq_file through seq_read_iter()
 *      into a kernel buffer, drop lines matching any hide rule for the
 *      calling uid, then serve the filtered buffer.
 *
 * Side-channel policy (why we differ from Kasumi):
 *   - mountinfo: mount IDs are PRESERVED, never renumbered.  Kasumi compacts
 *     mount IDs and then uses a kprobe on cp_statx to project the fake IDs
 *     into statx(STATX_MNT_ID).  Without kprobe we cannot rewrite statx, so
 *     renumbering would desync mountinfo from statx -> detectable.  Keeping
 *     the original IDs keeps mountinfo consistent with statx and with the
 *     shared:/master:/propagate_from: propagation references.  A visible
 *     child of a hidden mount is reparented to its nearest visible ancestor.
 *   - smaps: dropping only the VMA header leaves the Size:/Rss:/... block
 *     orphaned -> malformed output.  We drop the whole VMA block instead.
 *   - statfs f_type spoofing is complete, not best-effort: STATX_FSTYPE has
 *     never been merged into mainline (not even in v6.12), so statx() has no
 *     stx_fstype field and cannot leak the real s_magic.  Every f_type reader
 *     -- statfs(2), fstatfs(2), statvfs(3) -- funnels through sb->s_op->statfs,
 *     which we hijack and override; the mountinfo/mounts fstype field is
 *     covered by the line filters.  No un-hooked read path exposes s_magic.
 *   - per-pid lazy gap: pid dirs cached before the root hook was installed
 *     never pass through the root lookup, so on activation we actively walk
 *     /proc and hijack every already-cached pid dir's i_op
 *     (nm_hide_hijack_existing_pid_dirs); not-yet-cached dirs stay covered by
 *     the lazy root lookup hook.
 *   - non-target uid bypass: read/read_iter/llseek short-circuit to the
 *     original file_operations for any reader whose uid has no proc-kind rule
 *     (nm_hide_uid_targeted), so unrelated uids observe zero buffering,
 *     filtering, and timing difference.  A uid-0 rule targets everyone and
 *     disables the bypass.
 *
 * NM_HIDE_STATFS rides on the existing superblock hijack (nm_sop) and
 * overrides f_type via nomount_hijacked_statfs().
 */

static struct kmem_cache *nm_hide_fop_cachep __read_mostly, *nm_hide_iop_cachep __read_mostly;
static DEFINE_STATIC_KEY_FALSE(nomount_hide_proc_active);
static DEFINE_STATIC_KEY_FALSE(nomount_hide_statfs_active);
static LIST_HEAD(nm_hide_fop_list);
static LIST_HEAD(nm_hide_iop_list);
static LIST_HEAD(nm_hide_files);
static DEFINE_SPINLOCK(nm_hide_files_lock);

/* uid -> "has a proc-kind hide rule" fast-path table.  nm_hide_uid_targeted()
 * gates the read/read_iter/llseek bypass: a reader whose uid has no rule is
 * served straight from the original file_operations with zero buffering,
 * filtering, and timing difference.  uid-0 rules match every uid, so any such
 * rule forces everyone through the filter (nomount_hide_uid_all). */
static DEFINE_IDR(nomount_hide_uid_idr);
static DEFINE_STATIC_KEY_FALSE(nomount_hide_uid_all);
#define NM_HIDE_PROC_KINDS (NM_HIDE_MOUNTINFO | NM_HIDE_MOUNTS | NM_HIDE_MAPS | NM_HIDE_SMAPS)

struct nm_hide_file {
    struct list_head list;
    struct file *real;          /* underlying file (private_data stays the seq_file) */
    u32 kinds;                  /* which NM_HIDE_* this file filters */
    struct mutex lock;
    char *out;                  /* filtered content */
    size_t out_len;
    loff_t out_pos;
    bool loaded;
    bool failed;
};

struct nm_hide_fop {
    struct file_operations fake_fop; /* MUST be exactly at offset 0 */
    const struct file_operations *orig_fop;
    struct inode *inode;             /* inode whose f_op we replaced */
    u32 kinds;
    struct list_head list;
    struct rcu_head rcu;
};

struct nm_hide_iop {
    struct inode_operations fake_iop; /* MUST be exactly at offset 0 */
    const struct inode_operations *orig_iop;
    struct inode *inode;
    struct list_head list;
    struct rcu_head rcu;
};

#define NM_HIDE_MAX_LINES 1024
#define NM_HIDE_MAX_FILE  (16 * 1024 * 1024)
#define NM_HIDE_CHUNK     4096

static bool nm_hide_field_match(const char *line, size_t len, const char *path, size_t plen)
{
    size_t i = 0;

    if (unlikely(!plen || !line)) return false;
    while (i < len) {
        size_t s, fl;
        while (i < len && (line[i] == ' ' || line[i] == '\t')) i++;
        s = i;
        while (i < len && line[i] != ' ' && line[i] != '\t' && line[i] != '\n' && line[i] != '\r') i++;
        fl = i - s;
        /* exact field match, or path is a directory prefix of the field */
        if (fl >= plen && !memcmp(line + s, path, plen)) {
            if (fl == plen) return true;
            if (line[s + plen] == '/' || line[s + plen] == '\\') return true;
        }
    }
    return false;
}

static bool nm_hide_drop_line(const char *line, size_t len, u32 kinds, uid_t uid)
{
    const struct nomount_hide_rule *r;
    bool drop = false;

    if (unlikely(!line || !len)) return false;
    rcu_read_lock();
    list_for_each_entry_rcu(r, &nomount_hide_list, list) {
        if ((r->flags & kinds) && r->len &&
            (r->target_uid == 0 || r->target_uid == uid) &&
            nm_hide_field_match(line, len, r->path, r->len)) {
            drop = true;
            break;
        }
    }
    rcu_read_unlock();
    return drop;
}

static bool nm_hide_rule_kind_active(u32 kinds)
{
    const struct nomount_hide_rule *r;
    bool active = false;

    rcu_read_lock();
    list_for_each_entry_rcu(r, &nomount_hide_list, list) {
        if (r->flags & kinds) { active = true; break; }
    }
    rcu_read_unlock();
    return active;
}

/* O(1) gate used by the read/read_iter/llseek bypass.  A reader whose uid is
 * not targeted by any proc-kind rule is served straight from the original
 * file_operations, so non-targeted uids observe zero buffering, filtering, and
 * timing difference.  uid-0 rules match every uid and force everyone through
 * the filter. */
static bool nm_hide_uid_targeted(uid_t uid)
{
    bool t;

    if (static_branch_unlikely(&nomount_hide_uid_all)) return true;
    if (!static_branch_unlikely(&nomount_hide_proc_active)) return false;
    rcu_read_lock();
    t = idr_find(&nomount_hide_uid_idr, uid) != NULL;
    rcu_read_unlock();
    return t;
}

static int nm_parse_int(const char *s, size_t len)
{
    size_t i = 0;
    int v = 0;

    while (i < len && (s[i] == ' ' || s[i] == '\t')) i++;
    while (i < len && s[i] >= '0' && s[i] <= '9') {
        v = v * 10 + (s[i] - '0');
        if (v > 100000000) return 0;
        i++;
    }
    return v;
}

static void nm_parse_mount_ids(const char *line, size_t len, int *id, int *parent)
{
    size_t i = 0;

    while (i < len && (line[i] == ' ' || line[i] == '\t')) i++;
    *id = nm_parse_int(line + i, len - i);
    while (i < len && line[i] != ' ' && line[i] != '\t') i++;
    while (i < len && (line[i] == ' ' || line[i] == '\t')) i++;
    *parent = nm_parse_int(line + i, len - i);
}

/* Nearest visible ancestor of mount id `p`, or 0 (namespace root) if none.
 * ids/parents/vis describe every mountinfo line in file order; hidden lines
 * (vis == false) are skipped while walking up, so a visible child of a hidden
 * mount is reparented without ever leaking the hidden mount's id. */
static int nm_hide_nearest_visible(int p, const int *ids, const int *parents,
                                   const bool *vis, int cnt)
{
    int depth = 0;

    while (p > 0 && depth++ < cnt) {
        int idx = -1, k;
        for (k = 0; k < cnt; k++) {
            if (ids[k] == p) { idx = k; break; }
        }
        if (idx < 0) return 0; /* parent line absent => treat as namespace root */
        if (vis[idx]) return p;
        p = parents[idx];
    }
    return 0;
}

/* mountinfo line: preserve the mount id (field 1), rewrite only the parent id
 * (field 2) to `npid`.  Preserving ids keeps mountinfo consistent with
 * statx(STATX_MNT_ID) and with shared:/master:/propagate_from: references. */
static void nm_hide_emit_mountinfo_line(char *out, size_t *o, size_t cap,
                                        const char *line, size_t ll, int npid)
{
    size_t i = 0, f2s, f2e, used;
    char tmp[64];

    while (i < ll && (line[i] == ' ' || line[i] == '\t')) i++;
    while (i < ll && line[i] != ' ' && line[i] != '\t') i++; /* field 1: mount id */
    while (i < ll && (line[i] == ' ' || line[i] == '\t')) i++;
    f2s = i; while (i < ll && line[i] != ' ' && line[i] != '\t') i++; f2e = i;

    used = scnprintf(tmp, sizeof(tmp), "%d", npid);
    if (*o + f2s + used + (ll - f2e) + 1 > cap) return;
    memcpy(out + *o, line, f2s); *o += f2s;
    memcpy(out + *o, tmp, used); *o += used;
    memcpy(out + *o, line + f2e, ll - f2e); *o += ll - f2e;
    out[(*o)++] = '\n';
}

/* True if `line` looks like a smaps VMA header: first field is
 * "hex-start-hex-end" (e.g. 55aa-55bb).  The Size:/Rss:/... lines that follow
 * start with an alpha key and are NOT headers. */
static bool nm_hide_smaps_header(const char *line, size_t len)
{
    size_t i = 0, s;
    bool dash = false;

    while (i < len && (line[i] == ' ' || line[i] == '\t')) i++;
    s = i;
    while (i < len && line[i] != ' ' && line[i] != '\t' && line[i] != '\n') i++;
    if (i - s < 3) return false;
    for (; s < i; s++) {
        char c = line[s];
        if (c == '-') {
            if (dash) return false;
            dash = true;
        } else if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') ||
                     (c >= 'A' && c <= 'F'))) {
            return false;
        }
    }
    return dash;
}

static int nm_hide_filter(struct nm_hide_file *hf, const char *src, size_t len)
{
    struct hide_line { size_t off, len; } *kept;
    int *all_id = NULL, *all_parent = NULL;
    bool *vis = NULL;
    int kept_cnt = 0, all_cnt = 0;
    bool any_dropped = false, smaps_dropping = false;
    uid_t uid = current_uid().val;
    u32 kinds = hf->kinds;
    bool want_mi = !!(kinds & NM_HIDE_MOUNTINFO);
    bool want_smaps = !!(kinds & NM_HIDE_SMAPS);
    size_t i = 0, o = 0;
    char *out = NULL;
    int err = 0;

    /* The tables can be large (up to ~25KB in total); keep them off the kernel
     * stack, which is only 8-16KB. */
    kept = kmalloc_array(NM_HIDE_MAX_LINES, sizeof(*kept), GFP_KERNEL);
    if (!kept) return -ENOMEM;
    if (want_mi) {
        all_id = kcalloc(NM_HIDE_MAX_LINES, sizeof(int), GFP_KERNEL);
        all_parent = kcalloc(NM_HIDE_MAX_LINES, sizeof(int), GFP_KERNEL);
        vis = kcalloc(NM_HIDE_MAX_LINES, sizeof(bool), GFP_KERNEL);
        if (!all_id || !all_parent || !vis) { err = -ENOMEM; goto out; }
    }

    /* Fast path: no hide rules active (e.g. fop left over after rules were
     * cleared) — return the source verbatim. */
    if (!static_branch_unlikely(&nomount_hide_proc_active)) {
        out = kmalloc(len + 2, GFP_KERNEL);
        if (!out) { err = -ENOMEM; goto out; }
        memcpy(out, src, len);
        hf->out = out;
        hf->out_len = len;
        hf->out_pos = 0;
        hf->loaded = true;
        goto out;
    }

    /* Pass 1: classify lines.
     * mountinfo keeps a per-line id/parent/visible table (needed to reparent
     * visible children of hidden mounts without leaking hidden ids).
     * smaps drops whole VMA blocks: when a header matches a rule, every
     * following non-header line is dropped until the next header, so no
     * orphaned Size:/Rss:/... metadata survives. */
    while (i < len) {
        size_t s = i, ll;
        bool drop, is_hdr;
        int id = 0, pid = 0;

        while (i < len && src[i] != '\n') i++;
        ll = i - s;
        if (i < len) i++; /* consume '\n' */

        is_hdr = want_smaps && nm_hide_smaps_header(src + s, ll);
        if (is_hdr) {
            drop = nm_hide_drop_line(src + s, ll, kinds, uid);
            smaps_dropping = drop;
        } else {
            drop = want_smaps ? smaps_dropping :
                                nm_hide_drop_line(src + s, ll, kinds, uid);
        }

        if (want_mi && all_cnt < NM_HIDE_MAX_LINES) {
            nm_parse_mount_ids(src + s, ll, &id, &pid);
            all_id[all_cnt] = id;
            all_parent[all_cnt] = pid;
            vis[all_cnt] = !drop;
            all_cnt++;
        }

        if (drop) {
            any_dropped = true;
            continue;
        }
        if (kept_cnt < NM_HIDE_MAX_LINES) {
            kept[kept_cnt].off = s;
            kept[kept_cnt].len = ll;
            kept_cnt++;
        }
    }

    out = kmalloc(len + 2, GFP_KERNEL);
    if (!out) { err = -ENOMEM; goto out; }

    if (!any_dropped) {
        memcpy(out, src, len);
        o = len;
    } else if (want_mi) {
        int k;
        for (k = 0; k < kept_cnt; k++) {
            int id, pid;
            nm_parse_mount_ids(src + kept[k].off, kept[k].len, &id, &pid);
            nm_hide_emit_mountinfo_line(out, &o, len + 2, src + kept[k].off,
                                        kept[k].len,
                                        nm_hide_nearest_visible(pid, all_id,
                                                                all_parent, vis,
                                                                all_cnt));
        }
    } else {
        int k;
        for (k = 0; k < kept_cnt; k++) {
            size_t ll = kept[k].len;
            if (o + ll + 1 > len + 2) break;
            memcpy(out + o, src + kept[k].off, ll); o += ll;
            out[o++] = '\n';
        }
    }

    hf->out = out;
    hf->out_len = o;
    hf->out_pos = 0;
    hf->loaded = true;
out:
    kfree(vis);
    kfree(all_parent);
    kfree(all_id);
    kfree(kept);
    return err;
}

static int nm_hide_load_file(struct nm_hide_file *hf)
{
    size_t cap = 65536, len = 0;
    char *src;
    int ret = 0;

    src = kmalloc(cap, GFP_KERNEL);
    if (!src) return -ENOMEM;

    for (;;) {
        struct kvec kvec;
        struct iov_iter iter;
        struct kiocb iocb;
        ssize_t n;

        if (len + NM_HIDE_CHUNK > cap) {
            char *n2 = krealloc(src, cap * 2, GFP_KERNEL);
            if (!n2) { ret = -ENOMEM; break; }
            src = n2;
            cap *= 2;
        }
        kvec.iov_base = src + len;
        kvec.iov_len = NM_HIDE_CHUNK;
        iov_iter_kvec(&iter, READ, &kvec, 1, NM_HIDE_CHUNK);
        init_sync_kiocb(&iocb, hf->real);
        iocb.ki_pos = len;
        n = seq_read_iter(&iocb, &iter);
        if (n < 0) { ret = n; break; }
        if (n == 0) break;
        len += n;
        if (len > NM_HIDE_MAX_FILE) { ret = -E2BIG; break; }
    }

    if (!ret)
        ret = nm_hide_filter(hf, src, len);
    kfree(src);
    return ret;
}

static int nm_hide_ensure_loaded(struct nm_hide_file *hf)
{
    int ret = 0;

    if (READ_ONCE(hf->loaded)) return 0;
    mutex_lock(&hf->lock);
    if (hf->loaded) goto out;
    if (hf->failed) { ret = -EIO; goto out; }

    ret = nm_hide_load_file(hf);
    if (ret) hf->failed = true;
out:
    mutex_unlock(&hf->lock);
    return ret;
}

static struct nm_hide_file *nm_hide_file_get(struct file *file)
{
    struct nm_hide_file *hf;

    spin_lock(&nm_hide_files_lock);
    list_for_each_entry(hf, &nm_hide_files, list) {
        if (hf->real == file) {
            spin_unlock(&nm_hide_files_lock);
            return hf;
        }
    }
    spin_unlock(&nm_hide_files_lock);
    return NULL;
}

static int nm_hide_open(struct inode *inode, struct file *file)
{
    struct nm_hide_fop *fop = __get_nm(file->f_op, struct nm_hide_fop, fake_fop, read, nm_hide_read);
    struct nm_hide_file *hf;
    int ret;

    if (unlikely(!fop || !fop->orig_fop || !fop->orig_fop->open)) return -EINVAL;
    ret = fop->orig_fop->open(inode, file);
    if (ret) return ret;

    hf = kzalloc(sizeof(*hf), GFP_KERNEL);
    if (!hf) return -ENOMEM;
    hf->real = file;
    hf->kinds = fop->kinds;
    mutex_init(&hf->lock);
    spin_lock(&nm_hide_files_lock);
    list_add_tail(&hf->list, &nm_hide_files);
    spin_unlock(&nm_hide_files_lock);
    return 0;
}

static int nm_hide_release(struct inode *inode, struct file *file)
{
    struct nm_hide_fop *fop = __get_nm(file->f_op, struct nm_hide_fop, fake_fop, read, nm_hide_read);
    struct nm_hide_file *hf = nm_hide_file_get(file);

    if (hf) {
        spin_lock(&nm_hide_files_lock);
        list_del(&hf->list);
        spin_unlock(&nm_hide_files_lock);
        mutex_lock(&hf->lock);
        kfree(hf->out);
        mutex_unlock(&hf->lock);
        kfree(hf);
    }
    if (fop && fop->orig_fop && fop->orig_fop->release)
        return fop->orig_fop->release(inode, file);
    return 0;
}

static ssize_t nm_hide_read(struct file *file, char __user *buf, size_t count, loff_t *ppos)
{
    struct nm_hide_fop *fop = __get_nm(file->f_op, struct nm_hide_fop, fake_fop, read, nm_hide_read);
    struct nm_hide_file *hf;
    size_t avail, n;
    int err;

    if (unlikely(!fop)) return -EINVAL;
    /* Non-targeted reader: passthrough with zero buffering/filtering/timing. */
    if (!nm_hide_uid_targeted(current_uid().val)) {
        if (fop->orig_fop && fop->orig_fop->read)
            return fop->orig_fop->read(file, buf, count, ppos);
        return -EINVAL;
    }
    hf = nm_hide_file_get(file);
    if (!hf) return -EINVAL;
    err = nm_hide_ensure_loaded(hf);
    if (err) return err;
    if (hf->out_pos >= hf->out_len) return 0;
    avail = hf->out_len - hf->out_pos;
    n = min(count, avail);
    if (!n) return 0;
    if (copy_to_user(buf, hf->out + hf->out_pos, n)) return -EFAULT;
    hf->out_pos += n;
    *ppos = hf->out_pos;
    return n;
}

static ssize_t nm_hide_read_iter(struct kiocb *iocb, struct iov_iter *to)
{
    struct file *file = iocb->ki_filp;
    struct nm_hide_fop *fop = __get_nm(file->f_op, struct nm_hide_fop, fake_fop, read_iter, nm_hide_read_iter);
    struct nm_hide_file *hf;
    size_t avail, n;
    int err;

    if (unlikely(!fop)) return -EINVAL;
    /* Non-targeted reader: passthrough with zero buffering/filtering/timing. */
    if (!nm_hide_uid_targeted(current_uid().val)) {
        if (fop->orig_fop && fop->orig_fop->read_iter)
            return fop->orig_fop->read_iter(iocb, to);
        return -EINVAL;
    }
    hf = nm_hide_file_get(file);
    if (!hf) return -EINVAL;
    err = nm_hide_ensure_loaded(hf);
    if (err) return err;
    if (hf->out_pos >= hf->out_len) return 0;
    avail = hf->out_len - hf->out_pos;
    n = min(iov_iter_count(to), avail);
    if (!n) return 0;
    if (copy_to_iter(hf->out + hf->out_pos, n, to) != n) return -EFAULT;
    hf->out_pos += n;
    iocb->ki_pos = hf->out_pos;
    return n;
}

static loff_t nm_hide_llseek(struct file *file, loff_t offset, int whence)
{
    struct nm_hide_fop *fop = __get_nm(file->f_op, struct nm_hide_fop, fake_fop, llseek, nm_hide_llseek);
    struct nm_hide_file *hf;
    loff_t base, np;
    int err;

    if (unlikely(!fop)) return -EINVAL;
    /* Non-targeted reader: passthrough with zero buffering/filtering/timing. */
    if (!nm_hide_uid_targeted(current_uid().val)) {
        if (fop->orig_fop && fop->orig_fop->llseek)
            return fop->orig_fop->llseek(file, offset, whence);
        return -EINVAL;
    }
    hf = nm_hide_file_get(file);
    if (!hf) return -EINVAL;
    err = nm_hide_ensure_loaded(hf);
    if (err) return err;
    switch (whence) {
    case SEEK_SET: base = 0; break;
    case SEEK_CUR: base = hf->out_pos; break;
    case SEEK_END: base = hf->out_len; break;
    default: return -EINVAL;
    }
    np = base + offset;
    if (np < 0) return -EINVAL;
    hf->out_pos = np;
    return np;
}

static int nm_hide_attach_fop(struct inode *inode, u32 kinds)
{
    const struct file_operations *orig;
    struct nm_hide_fop *fop;

    if (unlikely(!inode || !inode->i_fop)) return -EINVAL;
    if (__get_nm(smp_load_acquire(&inode->i_fop), struct nm_hide_fop, fake_fop, read, nm_hide_read))
        return 0; /* already attached */

    orig = inode->i_fop;
    fop = kmem_cache_zalloc(nm_hide_fop_cachep, GFP_KERNEL);
    if (!fop) return -ENOMEM;
    fop->fake_fop = *orig;
    fop->orig_fop = orig;
    fop->inode = inode;
    fop->kinds = kinds;
    fop->fake_fop.open = nm_hide_open;
    fop->fake_fop.release = nm_hide_release;
    fop->fake_fop.read = nm_hide_read;
    fop->fake_fop.read_iter = nm_hide_read_iter;
    fop->fake_fop.llseek = nm_hide_llseek;
    /* seq files don't support splice on stock kernels; keep it disabled */
    fop->fake_fop.splice_read = NULL;
    list_add_tail_rcu(&fop->list, &nm_hide_fop_list);
    smp_store_release(&inode->i_fop, &fop->fake_fop);
    nm_debug("Attached hide filter (kinds=0x%x) to ino=%lu\n", kinds, inode->i_ino);
    return 0;
}

static bool nm_hide_is_pid_name(const struct qstr *name)
{
    int i;
    if (unlikely(name->len == 0 || name->len > 10)) return false;
    for (i = 0; i < name->len; i++)
        if (name->name[i] < '0' || name->name[i] > '9') return false;
    return true;
}

static void nm_hide_try_attach_proc_file(struct inode *inode, const struct qstr *name)
{
    u32 kinds = 0;

    if (name->len == 6 && !memcmp(name->name, "mounts", 6))
        kinds = NM_HIDE_MOUNTS;
    else if (name->len == 9 && !memcmp(name->name, "mountinfo", 9))
        kinds = NM_HIDE_MOUNTINFO;
    else if (name->len == 4 && !memcmp(name->name, "maps", 4))
        kinds = NM_HIDE_MAPS;
    else if (name->len == 5 && !memcmp(name->name, "smaps", 5))
        kinds = NM_HIDE_SMAPS;
    else
        return;

    if (!nm_hide_rule_kind_active(kinds)) return;
    nm_hide_attach_fop(inode, kinds);
}

static struct dentry *nm_hide_pid_dir_lookup(struct inode *dir, struct dentry *dentry, unsigned int flags)
{
    struct nm_hide_iop *h = __get_nm(smp_load_acquire(&dir->i_op), struct nm_hide_iop, fake_iop, lookup, nm_hide_pid_dir_lookup);
    struct dentry *res;

    if (unlikely(!h || !h->orig_iop || !h->orig_iop->lookup)) return ERR_PTR(-EOPNOTSUPP);
    res = h->orig_iop->lookup(dir, dentry, flags);
    if (!IS_ERR_OR_NULL(res)) {
        struct inode *inode = d_inode(res);
        if (inode && S_ISREG(inode->i_mode))
            nm_hide_try_attach_proc_file(inode, &dentry->d_name);
    }
    return res;
}

static void nm_hide_hijack_pid_dir(struct inode *inode)
{
    struct nm_hide_iop *h;
    if (unlikely(!inode || !inode->i_op ||
                 __get_nm(smp_load_acquire(&inode->i_op), struct nm_hide_iop, fake_iop, lookup, nm_hide_pid_dir_lookup)))
        return;
    h = kmem_cache_zalloc(nm_hide_iop_cachep, GFP_KERNEL);
    if (!h) return;
    h->fake_iop = *(inode->i_op);
    h->orig_iop = inode->i_op;
    h->inode = inode;
    h->fake_iop.lookup = nm_hide_pid_dir_lookup;
    list_add_tail_rcu(&h->list, &nm_hide_iop_list);
    smp_store_release(&inode->i_op, &h->fake_iop);
}

/*
 * /proc/self and /proc/thread-self resolve to the reader's own pid dir via
 * get_link(), which never passes through nm_hide_proc_root_lookup(). If that
 * pid dir dentry is already cached (the common case for long-running apps),
 * its inode i_op would otherwise never be hijacked and /proc/self/mounts,
 * /proc/self/maps etc. would bypass the hide filter. Hook the symlink inode's
 * get_link to grab the pid dir dentry directly (d_lookup, no path walk) and
 * hijack it. A cache miss is covered for free: the resolved target walk right
 * after this returns goes through the /proc root hook, which hijacks it.
 */
static const char *nm_hide_self_get_link(struct dentry *dentry, struct inode *inode,
                                         struct delayed_call *done)
{
    struct nm_hide_iop *h = __get_nm(smp_load_acquire(&inode->i_op), struct nm_hide_iop,
                                     fake_iop, get_link, nm_hide_self_get_link);
    const char *target;

    if (unlikely(!h || !h->orig_iop || !h->orig_iop->get_link))
        return ERR_PTR(-EOPNOTSUPP);
    target = h->orig_iop->get_link(dentry, inode, done);
    if (!IS_ERR(target)) {
        char buf[32];
        struct qstr qname;
        struct dentry *pd;
        int n = scnprintf(buf, sizeof(buf), "%u",
                          task_tgid_nr_ns(current, task_active_pid_ns(current)));
        qname.name = buf;
        qname.len = n;
        qname.hash = full_name_hash(dentry->d_parent, buf, n);
        pd = d_lookup(dentry->d_parent, &qname);
        if (pd) {
            struct inode *pin = d_inode(pd);
            if (pin && S_ISDIR(pin->i_mode))
                nm_hide_hijack_pid_dir(pin);
            dput(pd);
        }
    }
    return target;
}

static int nm_hide_hijack_self_link(const char *path)
{
    struct path p;
    struct inode *inode;
    struct nm_hide_iop *h;

    if (kern_path(path, 0, &p)) return 0; /* nofollow: keep the symlink inode */
    inode = d_backing_inode(p.dentry);
    if (!inode || !inode->i_op || !inode->i_op->get_link ||
        __get_nm(smp_load_acquire(&inode->i_op), struct nm_hide_iop, fake_iop,
                 get_link, nm_hide_self_get_link)) {
        path_put(&p);
        return 0;
    }
    h = kmem_cache_zalloc(nm_hide_iop_cachep, GFP_KERNEL);
    if (!h) { path_put(&p); return -ENOMEM; }
    h->fake_iop = *(inode->i_op);
    h->orig_iop = inode->i_op;
    h->inode = inode;
    h->fake_iop.get_link = nm_hide_self_get_link;
    list_add_tail_rcu(&h->list, &nm_hide_iop_list);
    smp_store_release(&inode->i_op, &h->fake_iop);
    path_put(&p);
    return 0;
}

static struct dentry *nm_hide_proc_root_lookup(struct inode *dir, struct dentry *dentry, unsigned int flags)
{
    struct nm_hide_iop *h = __get_nm(smp_load_acquire(&dir->i_op), struct nm_hide_iop, fake_iop, lookup, nm_hide_proc_root_lookup);
    struct dentry *res;

    if (unlikely(!h || !h->orig_iop || !h->orig_iop->lookup)) return ERR_PTR(-EOPNOTSUPP);
    res = h->orig_iop->lookup(dir, dentry, flags);
    if (!IS_ERR_OR_NULL(res)) {
        struct inode *inode = d_inode(res);
        if (inode && S_ISDIR(inode->i_mode) && nm_hide_is_pid_name(&dentry->d_name))
            nm_hide_hijack_pid_dir(inode);
    }
    return res;
}

/*
 * Active per-pid traversal.  When hide rules activate, pid dirs that were
 * already cached before the root lookup hook was installed never pass through
 * nm_hide_proc_root_lookup(), so their inode i_op would never be hijacked and
 * /proc/<pid>/mounts|maps|... would bypass the filter.  We walk the existing
 * pid dir dentries and hijack them up front; dirs not yet cached are left to
 * the lazy root hook.  Two phases because the filldir callback runs under
 * rcu/i_rwsem (proc_pid_readdir) and must not sleep, while
 * nm_hide_hijack_pid_dir() takes a GFP_KERNEL allocation: phase 1 only takes
 * inode references, phase 2 (normal context) does the hijack.
 */
#define NM_HIDE_MAX_PID_SCAN 2048

struct nm_hide_pid_scan {
    struct dir_context ctx;
    struct dentry *root;
    struct inode **inos; /* heap-allocated: 2048 * 8B would blow the 8KB stack */
    int cap;
    int cnt;
};

static bool nm_hide_pid_scan_actor(struct dir_context *ctx, const char *name, int namlen,
                                   loff_t off, u64 ino, unsigned int d_type)
{
    struct nm_hide_pid_scan *s = container_of(ctx, struct nm_hide_pid_scan, ctx);
    struct qstr qname;
    struct dentry *pd;
    int i;

    if (namlen <= 0 || namlen > 10 || s->cnt >= s->cap) return true;
    for (i = 0; i < namlen; i++)
        if (name[i] < '0' || name[i] > '9') return true;

    qname.name = name;
    qname.len = namlen;
    qname.hash = full_name_hash(s->root, name, namlen);
    pd = d_lookup(s->root, &qname);
    if (pd) {
        struct inode *inode = d_inode(pd);
        if (inode && S_ISDIR(inode->i_mode)) {
            ihold(inode);
            s->inos[s->cnt++] = inode;
        }
        dput(pd);
    }
    return true;
}

static void nm_hide_hijack_existing_pid_dirs(void)
{
    struct nm_hide_pid_scan s = { .ctx = { .actor = nm_hide_pid_scan_actor } };
    struct file *dir;
    int i;

    s.cap = NM_HIDE_MAX_PID_SCAN;
    s.inos = kmalloc_array(s.cap, sizeof(*s.inos), GFP_KERNEL);
    if (!s.inos) return;
    dir = filp_open("/proc", O_RDONLY | O_DIRECTORY, 0);
    if (IS_ERR(dir)) { kfree(s.inos); return; }
    s.root = dir->f_path.dentry;
    iterate_dir(dir, &s.ctx);
    fput(dir);

    for (i = 0; i < s.cnt; i++) {
        nm_hide_hijack_pid_dir(s.inos[i]);
        iput(s.inos[i]);
    }
    kfree(s.inos);
}

static int nm_hide_setup_proc_hooks(void)
{
    struct path p;
    struct inode *inode;
    struct nm_hide_iop *h;

    if (kern_path("/proc", LOOKUP_FOLLOW, &p)) return -ENOENT;
    inode = d_backing_inode(p.dentry);
    if (__get_nm(smp_load_acquire(&inode->i_op), struct nm_hide_iop, fake_iop, lookup, nm_hide_proc_root_lookup)) {
        path_put(&p);
        return 0;
    }
    if (!inode->i_op) { path_put(&p); return -EOPNOTSUPP; }

    h = kmem_cache_zalloc(nm_hide_iop_cachep, GFP_KERNEL);
    if (!h) { path_put(&p); return -ENOMEM; }
    h->fake_iop = *(inode->i_op);
    h->orig_iop = inode->i_op;
    h->inode = inode;
    h->fake_iop.lookup = nm_hide_proc_root_lookup;
    list_add_tail_rcu(&h->list, &nm_hide_iop_list);
    smp_store_release(&inode->i_op, &h->fake_iop);
    path_put(&p);
    nm_debug("Hooked /proc root lookup for pid-dir hide attach\n");

    /* Cover /proc/self and /proc/thread-self, which resolve to the reader's
     * pid dir via get_link and would otherwise bypass the root hook. */
    nm_hide_hijack_self_link("/proc/self");
    nm_hide_hijack_self_link("/proc/thread-self");

    /* Close the per-pid lazy gap: pid dirs cached before this hook existed
     * would never pass through the root lookup, so hijack them up front. */
    nm_hide_hijack_existing_pid_dirs();
    return 0;
}

static int nomount_hide_set_sb_f_type(struct super_block *sb, u32 ftype)
{
    struct nm_sop *nm_sop;

    if (unlikely(!sb || !sb->s_op)) return -EINVAL;
    nm_sop = __get_nm(smp_load_acquire(&sb->s_op), struct nm_sop, fake_sop, destroy_inode, nomount_hijacked_destroy_inode);
    if (!nm_sop) {
        nomount_hijack_superblock(sb);
        nm_sop = __get_nm(smp_load_acquire(&sb->s_op), struct nm_sop, fake_sop, destroy_inode, nomount_hijacked_destroy_inode);
        if (!nm_sop) return -ENOMEM;
    }
    WRITE_ONCE(nm_sop->fake_f_type, ftype);
    return 0;
}

static int nm_hide_apply_rule(struct nomount_hide_rule *rule)
{
    int err = 0;

    if (rule->flags & (NM_HIDE_MOUNTINFO | NM_HIDE_MOUNTS | NM_HIDE_MAPS | NM_HIDE_SMAPS)) {
        err = nm_hide_setup_proc_hooks();
        if (err) return err;
        if (rule->flags & NM_HIDE_MOUNTS) {
            /* namespace-global /proc/mounts inode (covers df/mount/etc) */
            struct path p;
            if (kern_path("/proc/mounts", LOOKUP_FOLLOW, &p) == 0) {
                nm_hide_attach_fop(d_backing_inode(p.dentry), NM_HIDE_MOUNTS);
                path_put(&p);
            }
        }
    }
    if (rule->flags & NM_HIDE_STATFS) {
        struct path p;
        if (kern_path(rule->path, LOOKUP_FOLLOW, &p) == 0) {
            err = nomount_hide_set_sb_f_type(p.dentry->d_sb, rule->arg);
            path_put(&p);
        } else {
            err = -ENOENT;
        }
    }
    return err;
}

static void nm_hide_recalc_branches(void)
{
    const struct nomount_hide_rule *r;
    bool proc = false, statfs = false, uid_all = false;

    list_for_each_entry(r, &nomount_hide_list, list) {
        if (r->flags & NM_HIDE_PROC_KINDS) {
            proc = true;
            if (r->target_uid == 0) uid_all = true; /* uid-0 rule matches every uid */
        }
        if (r->flags & NM_HIDE_STATFS) statfs = true;
    }
    if (proc) static_branch_enable(&nomount_hide_proc_active);
    else static_branch_disable(&nomount_hide_proc_active);
    if (statfs) static_branch_enable(&nomount_hide_statfs_active);
    else static_branch_disable(&nomount_hide_statfs_active);
    if (uid_all) static_branch_enable(&nomount_hide_uid_all);
    else static_branch_disable(&nomount_hide_uid_all);
}

static void nm_hide_restore_all(void)
{
    struct nm_hide_fop *f, *ftmp;
    struct nm_hide_iop *h, *htmp;

    list_for_each_entry_safe(f, ftmp, &nm_hide_fop_list, list) {
        if (f->inode) smp_store_release(&f->inode->i_fop, f->orig_fop);
        list_del_rcu(&f->list);
        kfree_rcu(f, rcu);
    }
    list_for_each_entry_safe(h, htmp, &nm_hide_iop_list, list) {
        if (h->inode) smp_store_release(&h->inode->i_op, h->orig_iop);
        list_del_rcu(&h->list);
        kfree_rcu(h, rcu);
    }
}

/* Caller must hold nomount_rwsem (write). */
static int __nomount_add_hide_rule(u32 flags, unsigned int uid, u32 arg, const char *path, u16 len)
{
    struct nomount_hide_rule *rule, *ex;
    int err;

    if (!len || len >= PATH_MAX) return -EINVAL;
    if (!(flags & (NM_HIDE_MOUNTINFO | NM_HIDE_MOUNTS | NM_HIDE_MAPS | NM_HIDE_SMAPS | NM_HIDE_STATFS)))
        return -EINVAL;
    if ((flags & NM_HIDE_STATFS) && arg == 0) return -EINVAL;

    list_for_each_entry(ex, &nomount_hide_list, list) {
        if (ex->target_uid == uid && ex->flags == flags && ex->len == len &&
            !memcmp(ex->path, path, len))
            return -EEXIST;
    }

    rule = kmalloc(sizeof(*rule) + len + 1, GFP_KERNEL);
    if (!rule) return -ENOMEM;
    rule->target_uid = uid;
    rule->flags = flags;
    rule->arg = arg;
    rule->len = len;
    memcpy(rule->path, path, len);
    rule->path[len] = '\0';
    list_add_tail_rcu(&rule->list, &nomount_hide_list);
    nm_hide_recalc_branches();

    /* Track the target uid for the fast-path read bypass.  Only proc-kind
     * rules matter (statfs readers never go through the proc fop). */
    if ((rule->flags & NM_HIDE_PROC_KINDS) &&
        !idr_find(&nomount_hide_uid_idr, uid)) {
        if (idr_alloc(&nomount_hide_uid_idr, (void *)1, uid, uid + 1,
                      GFP_KERNEL) < 0) {
            list_del_rcu(&rule->list);
            kfree_rcu(rule, rcu);
            nm_hide_recalc_branches();
            return -ENOMEM;
        }
    }

    err = nm_hide_apply_rule(rule);
    if (err) {
        list_del_rcu(&rule->list);
        kfree_rcu(rule, rcu);
        nm_hide_recalc_branches();
    }
    return err;
}

/* Caller must hold nomount_rwsem (write). */
static void __nomount_del_hide_rule(unsigned int uid, const char *path, u16 len)
{
    struct nomount_hide_rule *r, *tmp;

    list_for_each_entry_safe(r, tmp, &nomount_hide_list, list) {
        if (r->target_uid == uid && r->len == len && !memcmp(r->path, path, len)) {
            list_del_rcu(&r->list);
            kfree_rcu(r, rcu);
        }
    }
    /* Drop the uid from the fast-path table once no proc-kind rule targets it. */
    {
        struct nomount_hide_rule *r2;
        bool still = false;
        list_for_each_entry(r2, &nomount_hide_list, list) {
            if (r2->target_uid == uid && (r2->flags & NM_HIDE_PROC_KINDS)) {
                still = true;
                break;
            }
        }
        if (!still) idr_remove(&nomount_hide_uid_idr, uid);
    }
    nm_hide_recalc_branches();
}

/* Caller must hold nomount_rwsem (write). */
static void __nomount_clear_hide_rules(bool exit)
{
    struct nomount_hide_rule *r, *tmp;

    list_for_each_entry_safe(r, tmp, &nomount_hide_list, list) {
        list_del_rcu(&r->list);
        kfree_rcu(r, rcu);
    }
    nm_hide_recalc_branches();
    /* recalc_branches() disabled nomount_hide_proc_active / uid_all, so new
     * readers skip idr_find(); wait for any in-flight rcu reader before
     * tearing down the tree (mirrors the nomount_uid_idr handling). */
    synchronize_rcu();
    idr_destroy(&nomount_hide_uid_idr);
    if (!exit) idr_init(&nomount_hide_uid_idr);
    if (exit) nm_hide_restore_all();
}

/*** Module Management ***/

static __always_inline struct nomount_dir_node *__nomount_alloc_dir_node(struct inode *inode) 
{
    struct nomount_dir_node *dir_node = kmem_cache_zalloc(nm_dir_cachep, GFP_KERNEL);
    if (unlikely(!dir_node)) return NULL;
    seqcount_init(&dir_node->seq); 
    return dir_node;
}

static void __nomount_inject_child_locked(struct nomount_dir_node *dir_node, struct nomount_rule *rule, const char *name, size_t name_len)
{
    struct nomount_child_array *new_arr, *old_arr;
    struct nomount_child_node *new_child, *existing_child;
    int old_count, capacity, new_cap, pos = 0;
    u32 target_hash;

    if (unlikely(!dir_node)) return;

    target_hash = full_name_hash((const void *)(unsigned long)NOMOUNT_MAGIC_SIG, name, name_len);
    if ((old_arr = dir_node->children) && (existing_child = nomount_bsearch_child(old_arr, name, name_len, target_hash))) {
        existing_child->rule = rule;
        rule->parent_dir = dir_node;
        return;
    }

    if (unlikely(!(new_child = kmalloc(sizeof(*new_child) + name_len + 1, GFP_KERNEL)))) return;

    rule->parent_dir = dir_node;
    new_child->fake_ino = rule->v_hash;
    new_child->name_hash = target_hash;
    new_child->d_type = (rule->flags & NM_FLAG_IS_DIR) ? DT_DIR : DT_REG;
    new_child->flags = rule->flags;
    new_child->name_len = name_len;
    new_child->rule = rule;
    memcpy(new_child->name, name, name_len);
    new_child->name[name_len] = '\0';
    old_count = old_arr ? old_arr->count : 0;
    capacity = old_arr ? old_arr->capacity : 0;

    if (old_arr)
        while (pos < old_count && old_arr->hashes[pos] < target_hash) pos++;

    if (old_count < capacity) {
        write_seqcount_begin(&dir_node->seq);
        if (pos < old_count) {
            for (int i = old_count; i > pos; i--) {
                WRITE_ONCE(old_arr->hashes[i], READ_ONCE(old_arr->hashes[i - 1]));
                WRITE_ONCE(old_arr->nodes[i], READ_ONCE(old_arr->nodes[i - 1]));
            }
        }
        WRITE_ONCE(old_arr->hashes[pos], target_hash);
        WRITE_ONCE(old_arr->nodes[pos], new_child);
        old_arr->count++;
        dir_node->bloom_mask |= (1ULL << (target_hash & 63));
        write_seqcount_end(&dir_node->seq);
        return;
    }

    new_cap = capacity == 0 ? 4 : capacity * 2;
    new_arr = kmalloc(sizeof(*new_arr) + (new_cap * sizeof(u32)) + (new_cap * sizeof(void *)), GFP_KERNEL);
    if (!new_arr) { kfree(new_child); return; }
    new_arr->hashes = (u32 *)(new_arr + 1);
    new_arr->nodes = (struct nomount_child_node **)(new_arr->hashes + new_cap);
    new_arr->capacity = new_cap;
    new_arr->count = old_count + 1;
    if (old_arr) {
        memcpy(new_arr->hashes, old_arr->hashes, pos * sizeof(u32));
        memcpy(new_arr->nodes, old_arr->nodes, pos * sizeof(void *));
        memcpy(&new_arr->hashes[pos + 1], &old_arr->hashes[pos], (old_count - pos) * sizeof(u32));
        memcpy(&new_arr->nodes[pos + 1], &old_arr->nodes[pos], (old_count - pos) * sizeof(void *));
    }
    new_arr->hashes[pos] = target_hash;
    new_arr->nodes[pos] = new_child;

    write_seqcount_begin(&dir_node->seq);
    rcu_assign_pointer(dir_node->children, new_arr);
    dir_node->bloom_mask |= (1ULL << (target_hash & 63));
    write_seqcount_end(&dir_node->seq);

    synchronize_srcu(&nomount_srcu);
    if (old_arr) kfree_rcu(old_arr, rcu);
}

static void __nomount_delete_child_locked(struct nomount_rule *rule)
{
    struct nomount_dir_node *dir_node = rule->parent_dir;
    struct nomount_child_node *child_to_free = NULL;
    struct nomount_child_array *old_arr;
    int old_count, target_idx = -1;
    u64 mask = 0;

    if (unlikely(!dir_node || !(old_arr = dir_node->children))) return;

    for (int i = 0; i < (old_count = old_arr->count); i++) {
        if (old_arr->nodes[i]->rule == rule) {
            target_idx = i;
            child_to_free = old_arr->nodes[i];
            break;
        }
    }
    if (target_idx == -1) return;

    write_seqcount_begin(&dir_node->seq);
    if (old_count == 1) {
        rcu_assign_pointer(dir_node->children, NULL);
        dir_node->bloom_mask = 0;
        write_seqcount_end(&dir_node->seq);
        synchronize_srcu(&nomount_srcu);
        kfree_rcu(old_arr, rcu);
        kfree_rcu(child_to_free, rcu);
        if (!(dir_node->_tag_ptr & 1UL) && !rcu_access_pointer(dir_node->iop) &&
             !rcu_access_pointer(dir_node->fop) && cmpxchg(&dir_node->v_inode, NULL, (struct inode *)-1L) == NULL)
            call_rcu(&dir_node->rcu, nm_dir_rcu_free);
        return;
    }

    if (target_idx < old_count - 1) {
        for (int i = target_idx; i < old_count - 1; i++) {
            WRITE_ONCE(old_arr->hashes[i], READ_ONCE(old_arr->hashes[i + 1]));
            WRITE_ONCE(old_arr->nodes[i], READ_ONCE(old_arr->nodes[i + 1]));
        }
    }
    old_arr->count--;

    for (int i = 0; i < old_arr->count; i++) mask |= (1ULL << (old_arr->hashes[i] & 63));
    dir_node->bloom_mask = mask;
    write_seqcount_end(&dir_node->seq);
    synchronize_srcu(&nomount_srcu);
    kfree_rcu(child_to_free, rcu);
}

static int nomount_generate_virtual_topology(struct nomount_rule *target_rule)
{
    struct nomount_rule *current_rule = target_rule, *ex;
    char *v_path = nm_get_vpath(target_rule);
    int p_len = target_rule->v_len;
    struct nomount_dir_node *dir_node;
    struct hlist_node *tmp;
    struct nomount_rule *irule;
    struct path p_path;
    int i, p, err = 0;
    HLIST_HEAD(pending_list);

    /* yeah, this have a lot of mixed declarations, idgaf */
    while (p_len > 1) {
        for (i = p_len - 1; i >= 0; i--)
            if (v_path[i] == '/') break; 

        int parent_len = (i == 0) ? 1 : i;
        const char *child_name = v_path + i + 1;
        size_t child_len = p_len - i - 1;
        u32 h_parent = full_name_hash((const void *)(unsigned long)NOMOUNT_MAGIC_SIG, v_path, parent_len);

        if ((ex = nm_tree_search_path(h_parent, parent_len, v_path))) {
            dir_node = ex->this_dir ? ex->this_dir : __nomount_alloc_dir_node(NULL);
            if (unlikely(!dir_node)) { err = -ENOMEM; break; }
            dir_node->_tag_ptr = (unsigned long)ex | 1UL;
            if (!ex->this_dir) ex->this_dir = dir_node;
            __nomount_inject_child_locked(dir_node, current_rule, child_name, child_len);
            break;
        }

        char orig_vpath = v_path[i];
        if (i > 0) v_path[i] = '\0';
        if ((p = kern_path((parent_len == 1) ? "/" : v_path, LOOKUP_FOLLOW, &p_path)), (v_path[i] = orig_vpath), (p == 0)) {
            struct inode *v_inode = d_backing_inode(p_path.dentry);
            dir_node = nomount_get_dir_node(v_inode);
            if (!dir_node) dir_node = __nomount_alloc_dir_node(v_inode);
            if (likely(dir_node)) {
                struct dentry *dentry;
                struct qstr qname = { .name = child_name, .len = child_len };
                (p_path.dentry->d_flags & DCACHE_OP_HASH) ? p_path.dentry->d_op->d_hash(p_path.dentry, &qname)
                 : (qname.hash = full_name_hash(p_path.dentry, child_name, child_len));

                nomount_hijack_dir_ops(dir_node, v_inode);
                nomount_hijack_superblock(p_path.dentry->d_sb);
                dentry = d_lookup(p_path.dentry, &qname);
                if (dentry) { d_drop(dentry); dput(dentry); }

                __nomount_inject_child_locked(dir_node, current_rule, child_name, child_len);
            } else {
                err = -ENOMEM;
            }
            path_put(&p_path);
            break;
        }

        if (!(irule = kmalloc(sizeof(struct nomount_rule) + parent_len + 1 + 2, GFP_KERNEL))) { err = -ENOMEM; break; }
        *irule = (struct nomount_rule){0};
        irule->v_len = parent_len;
        irule->v_hash = h_parent;
        irule->flags = NM_FLAG_IS_DIR | NM_FLAG_VIRTUAL_DIR;
        irule->v_ino = (unsigned long)h_parent;
        memcpy(nm_get_vpath(irule), v_path, parent_len);
        nm_get_vpath(irule)[parent_len] = '\0';
        nm_get_rpath(irule)[0] = '\0';

        if (unlikely(!(dir_node = __nomount_alloc_dir_node(NULL)))) {
            kfree(irule);
            err = -ENOMEM;
            break;
        }

        dir_node->_tag_ptr = (unsigned long)irule | 1UL;
        irule->this_dir = dir_node;
        __nomount_inject_child_locked(dir_node, current_rule, child_name, child_len);
        hlist_add_head(&irule->vpath_node, &pending_list);
        current_rule = irule;
        p_len = i;
    }

    hlist_for_each_entry_safe(irule, tmp, &pending_list, vpath_node) {
        hlist_del_init(&irule->vpath_node);
        (err == 0) ? nm_tree_insert(irule) : nm_free_rule(irule);
    }

    return err;
}

static void nm_detach_dir_node(struct nomount_dir_node *dir_node)
{
    struct nm_iop *iop;
    struct nm_fop *fop;
    if (!dir_node || (dir_node->_tag_ptr & 1UL)) return;

    rcu_read_lock();
    if ((iop = rcu_dereference(dir_node->iop))) WRITE_ONCE(iop->dir_node, NULL);
    if ((fop = rcu_dereference(dir_node->fop))) WRITE_ONCE(fop->dir_node, NULL);
    rcu_read_unlock();
}

static void nomount_prune_empty_virtual_dirs(struct nomount_dir_node *dir_node, struct hlist_head *victims)
{
    struct nomount_rule *owner;
    while (dir_node && (!dir_node->children || !dir_node->children->count) &&
           (owner = (dir_node->_tag_ptr & 1UL) ? (struct nomount_rule *)(dir_node->_tag_ptr & ~1UL) : NULL)) {

       if (!(owner->flags & NM_FLAG_VIRTUAL_DIR)) {
            owner->this_dir = NULL;
            if (cmpxchg(&dir_node->v_inode, NULL, (struct inode *)-1L) == NULL) {
                nm_detach_dir_node(dir_node);
                call_rcu(&dir_node->rcu, nm_dir_rcu_free);
            } else {
                WRITE_ONCE(dir_node->_tag_ptr, 1UL); 
            }
            break;
        }

        rb_erase_cached(&owner->rb_node, &nomount_rules_tree);
        if (owner->parent_dir) __nomount_delete_child_locked(owner);
        nm_debug("Pruned empty virtual directory: %s\n", nm_get_vpath(owner));
        dir_node = owner->parent_dir;
        hlist_add_head(&owner->vpath_node, victims);
    }
}

/*** Rule Operations ***/

static struct nomount_rule *nm_alloc_rule(const char *v_path, const char *r_path, u16 v_len, u16 r_len, u32 flags, unsigned int target_uid)
{
    struct nomount_rule *rule;
    bool is_whiteout = (flags & NM_FLAG_WHITEOUT);
    struct path v_path_struct;

    if (!v_path || (!r_path && !is_whiteout)) return ERR_PTR(-EINVAL);
    while (v_len > 1 && v_path[v_len - 1] == '/') { v_len--; }
    if (!is_whiteout) { while (r_len > 1 && r_path[r_len - 1] == '/') { r_len--; } }

    if (is_whiteout) r_len = 0;
    if (!(rule = kmalloc((sizeof(struct nomount_rule) + v_len + r_len + 2), GFP_KERNEL))) return ERR_PTR(-ENOMEM);

    *rule = (struct nomount_rule){0};
    rule->v_hash = full_name_hash((const void *)(unsigned long)NOMOUNT_MAGIC_SIG, v_path, v_len);
    rule->flags = flags;
    rule->v_len = v_len;
    rule->target_uid = target_uid;
    memcpy(nm_get_vpath(rule), v_path, v_len);
    nm_get_vpath(rule)[v_len] = '\0';
    if (!is_whiteout) memcpy(nm_get_rpath(rule), r_path, r_len);
    nm_get_rpath(rule)[r_len] = '\0';

    if (!is_whiteout && kern_path(nm_get_rpath(rule), LOOKUP_FOLLOW, &rule->r_path) == 0) {
        struct inode *real_inode = d_backing_inode(rule->r_path.dentry);
        if (likely(real_inode)) {
            real_inode->i_flags |= S_PRIVATE;
            if (S_ISDIR(real_inode->i_mode)) rule->flags |= NM_FLAG_IS_DIR;
        }
    }

    if (kern_path(nm_get_vpath(rule), LOOKUP_FOLLOW, &v_path_struct) == 0) {
        struct dentry *target_dentry = v_path_struct.dentry;
        rule->v_ino = d_backing_inode(target_dentry)->i_ino;
        d_drop(target_dentry);
        path_put(&v_path_struct);
    } else {
         rule->v_ino = (unsigned long)rule->v_hash;
    }

    return rule;
}

static void nm_free_rule(struct nomount_rule *rule)
{
    if (unlikely(!rule)) return;
    if (rule->r_path.dentry) path_put(&rule->r_path);
    if (rule->this_dir) {
        if (cmpxchg(&rule->this_dir->v_inode, NULL, (struct inode *)-1L) == NULL) {
            nm_detach_dir_node(rule->this_dir);
            call_rcu(&rule->this_dir->rcu, nm_dir_rcu_free);
        } else {
            WRITE_ONCE(rule->this_dir->_tag_ptr, 1UL);
        }
    }
    kfree(rule);
}

static void nm_detach_rule_locked(struct nomount_rule *rule, struct hlist_head *victims, bool prune)
{
    rb_erase_cached(&rule->rb_node, &nomount_rules_tree);
    if (rule->parent_dir) {
        __nomount_delete_child_locked(rule);
        if (prune) nomount_prune_empty_virtual_dirs(rule->parent_dir, victims); 
    }
    hlist_add_head(&rule->vpath_node, victims);
}

static int __nomount_add_rule(const char *v_path, const char *r_path, u16 v_len, u16 r_len, u32 flags, unsigned int target_uid)
{
    struct nomount_rule *rule, *existing, *victim_rule;
    struct hlist_node *tmp;
    HLIST_HEAD(victims);
    int err = 0;

    if (IS_ERR((rule = nm_alloc_rule(v_path, r_path, v_len, r_len, flags, target_uid))))
        return PTR_ERR(rule);

    down_write(&nomount_rwsem);
    if ((existing = nm_tree_search_exact(rule->v_hash, v_len, nm_get_vpath(rule), target_uid))) {
        if (existing->this_dir) {
            if (rule->this_dir) call_rcu(&rule->this_dir->rcu, nm_dir_rcu_free);
            rule->this_dir = existing->this_dir;
            if (rule->this_dir->_tag_ptr & 1UL) rule->this_dir->_tag_ptr = (unsigned long)rule | 1UL;
            existing->this_dir = NULL;
        }
        nm_detach_rule_locked(existing, &victims, false);
        nm_info("Shadowing existing rule for: %s\n", nm_get_vpath(rule));
    }

    if ((err = nomount_generate_virtual_topology(rule)) != 0) {
        up_write(&nomount_rwsem);
        nm_free_rule(rule); 
        synchronize_rcu(); synchronize_srcu(&nomount_srcu);
        hlist_for_each_entry_safe(victim_rule, tmp, &victims, vpath_node)
            nm_free_rule(victim_rule);
        return err;
    }

    nm_tree_insert(rule);
    up_write(&nomount_rwsem);

    if (!hlist_empty(&victims)) {
        synchronize_rcu(); synchronize_srcu(&nomount_srcu);
        hlist_for_each_entry_safe(victim_rule, tmp, &victims, vpath_node)
            nm_free_rule(victim_rule);
    }

    (flags & NM_FLAG_WHITEOUT) ? nm_info("Successfully added whiteout rule: %s\n", nm_get_vpath(rule))
    : nm_info("Successfully added injection rule: %s -> %s\n", nm_get_vpath(rule), nm_get_rpath(rule));
        
    return 0;
}

static void __nomount_del_rule(const char *v_path, size_t v_len, unsigned int target_uid, struct hlist_head *r_victims)
{
    u32 hash = full_name_hash((const void *)(unsigned long)NOMOUNT_MAGIC_SIG, v_path, v_len);
    struct nomount_rule *rule = nm_tree_search_exact(hash, v_len, v_path, target_uid);
    if (rule) nm_detach_rule_locked(rule, r_victims, true);
}

static void __nomount_clear_all(int clear_flags)
{
    struct nomount_rule *rule;
    struct hlist_node *tmp;
    HLIST_HEAD(r_victims);

    if (clear_flags & NM_CLEAR_UIDS) {
        static_branch_disable(&nomount_active_uids);
        synchronize_rcu();
        idr_destroy(&nomount_uid_idr);
        if (!(clear_flags & NM_CLEAR_EXIT)) idr_init(&nomount_uid_idr);
    }
    if (clear_flags & NM_CLEAR_RULES) {
        struct rb_node *node;
        while ((node = rb_first_cached(&nomount_rules_tree)) != NULL) {
            rule = rb_entry(node, struct nomount_rule, rb_node);
            nm_detach_rule_locked(rule, &r_victims, false);
        }
        synchronize_rcu(); synchronize_srcu(&nomount_srcu);
        hlist_for_each_entry_safe(rule, tmp, &r_victims, vpath_node) {
            nm_free_rule(rule);
        }
        __nomount_clear_hide_rules(!!(clear_flags & NM_CLEAR_EXIT));
    }

    if (clear_flags & NM_CLEAR_EXIT) nomount_restore_superblocks();
}

/*** Payload Communication API ***/

static int nm_process_payload(unsigned long user_addr)
{
    struct nm_payload *payload;
    struct page *page;
    unsigned long pg_off = offset_in_page(user_addr);
    char *buf_ptr, *buf_end;

    if (pg_off + sizeof(*payload) > PAGE_SIZE || get_user_pages_fast(user_addr, 1, FOLL_WRITE, &page) != 1) 
        return -EFAULT;

    if ((payload = (void *)((char *)kmap(page) + pg_off))->magic != NOMOUNT_MAGIC_SIG) {
        kunmap(page);
        put_page(page);
        return -EFAULT;
    }

    payload->status = 0;
    buf_ptr = payload->buffer + payload->arg1;
    buf_end = payload->buffer + (payload->data_size > sizeof(payload->buffer) ? sizeof(payload->buffer) : payload->data_size);

    switch (payload->cmd) {
        case NM_CMD_GET_VERSION:
            memcpy(payload->buffer, NOMOUNT_VERSION, (payload->data_size = strlen(NOMOUNT_VERSION)));
            break;

        case NM_CMD_ADD_RULE:
            if (payload->data_size > sizeof(payload->buffer)) { payload->status = -EINVAL; break; }
            while (buf_ptr + sizeof(struct nm_rule_hdr) <= buf_end) {
                struct nm_rule_hdr *h = (void *)buf_ptr;
                if ((buf_ptr += sizeof(*h)) + h->v_len + h->r_len > buf_end || unlikely(h->v_len >= PATH_MAX || h->r_len >= PATH_MAX)) break;
                payload->status = __nomount_add_rule(buf_ptr, buf_ptr + h->v_len, h->v_len, h->r_len, h->flags, h->uid);
                buf_ptr += h->v_len + h->r_len;
            }
            payload->arg1 = buf_ptr - payload->buffer;
            break;

        case NM_CMD_DEL_RULE: {
            HLIST_HEAD(r_victims);    
            if (payload->data_size > sizeof(payload->buffer)) { payload->status = -EINVAL; break; }
            down_write(&nomount_rwsem);
            while (buf_ptr + sizeof(struct nm_del_hdr) <= buf_end) {
                struct nm_del_hdr *h = (void *)buf_ptr;
                if ((buf_ptr += sizeof(*h)) + h->v_len > buf_end) break;
                __nomount_del_rule(buf_ptr, h->v_len, h->uid, &r_victims);
                buf_ptr += h->v_len;
            }
            up_write(&nomount_rwsem);
            payload->arg1 = buf_ptr - payload->buffer;

            if (!hlist_empty(&r_victims)) {
                struct nomount_rule *rule; struct hlist_node *tmp;
                synchronize_rcu(); synchronize_srcu(&nomount_srcu);
                hlist_for_each_entry_safe(rule, tmp, &r_victims, vpath_node) nm_free_rule(rule);
            } else payload->status = -ENOENT;
            break;
        }

        case NM_CMD_ADD_UID:
            down_write(&nomount_rwsem);
            payload->status = idr_find(&nomount_uid_idr, payload->target_uid) ? -EEXIST :
                              (idr_alloc(&nomount_uid_idr, (void *)8, payload->target_uid, payload->target_uid + 1, GFP_KERNEL) >= 0) ?
                              (static_branch_enable(&nomount_active_uids), 0) : -ENOMEM;
            up_write(&nomount_rwsem);
            break;

        case NM_CMD_DEL_UID:
            down_write(&nomount_rwsem);
            payload->status = !idr_find(&nomount_uid_idr, payload->target_uid) ? -ENOENT :
                              (idr_remove(&nomount_uid_idr, payload->target_uid), 
                               idr_is_empty(&nomount_uid_idr) ? static_branch_disable(&nomount_active_uids) : (void)0, 0);
            up_write(&nomount_rwsem);
            break;

        case NM_CMD_CLEAR_ALL:
        case NM_CMD_CLEAR_UIDS:
        case NM_CMD_CLEAR_RULES:
            down_write(&nomount_rwsem);
            __nomount_clear_all((payload->cmd == NM_CMD_CLEAR_ALL) ? (NM_CLEAR_UIDS | NM_CLEAR_RULES) :
                                (payload->cmd == NM_CMD_CLEAR_UIDS) ? NM_CLEAR_UIDS : NM_CLEAR_RULES);
            up_write(&nomount_rwsem);
            break;

        case NM_CMD_GET_LIST: {
            int current_idx = 0;
            buf_ptr = payload->buffer;
            buf_end = payload->buffer + sizeof(payload->buffer);

            down_read(&nomount_rwsem);
            for (struct rb_node *node = rb_first_cached(&nomount_rules_tree); node; node = rb_next(node)) {
                if (current_idx++ < payload->arg1) continue;
                struct nomount_rule *r = rb_entry(node, struct nomount_rule, rb_node);
                u16 r_len = r->flags & NM_FLAG_WHITEOUT ? 0 : strlen(nm_get_rpath(r));
                if (buf_ptr + sizeof(struct nm_rule_hdr) + r->v_len + r_len > buf_end) { current_idx--; break; }

                *(struct nm_rule_hdr *)buf_ptr = (struct nm_rule_hdr){.flags = r->flags, .uid = r->target_uid, .v_len = r->v_len, .r_len = r_len};
                buf_ptr += sizeof(struct nm_rule_hdr);
                memcpy(buf_ptr, nm_get_vpath(r), r->v_len); buf_ptr += r->v_len;
                if (r_len > 0) { memcpy(buf_ptr, nm_get_rpath(r), r_len); buf_ptr += r_len; }
            }
            up_read(&nomount_rwsem);
            payload->data_size = buf_ptr - payload->buffer;
            payload->arg1 = current_idx;
            break;
        }

        case NM_CMD_GET_UIDS: {
            u32 *out = (u32 *)payload->buffer;
            int count = 0;
            if (static_branch_unlikely(&nomount_active_uids)) {
                rcu_read_lock();
                while (count < sizeof(payload->buffer)/4 && idr_get_next(&nomount_uid_idr, &payload->arg1))
                    out[count++] = payload->arg1++;
                rcu_read_unlock();
            }
            payload->data_size = count * 4;
            break;
        }

        case NM_CMD_ADD_HIDE_RULE:
            if (payload->data_size > sizeof(payload->buffer)) { payload->status = -EINVAL; break; }
            down_write(&nomount_rwsem);
            while (buf_ptr + sizeof(struct nm_hide_rule_hdr) <= buf_end) {
                struct nm_hide_rule_hdr *h = (void *)buf_ptr;
                if ((buf_ptr += sizeof(*h)) + h->len > buf_end || unlikely(h->len >= PATH_MAX)) break;
                payload->status = __nomount_add_hide_rule(h->flags, h->uid, h->arg, buf_ptr, h->len);
                buf_ptr += h->len;
            }
            up_write(&nomount_rwsem);
            payload->arg1 = buf_ptr - payload->buffer;
            break;

        case NM_CMD_DEL_HIDE_RULE:
            if (payload->data_size > sizeof(payload->buffer)) { payload->status = -EINVAL; break; }
            down_write(&nomount_rwsem);
            while (buf_ptr + sizeof(struct nm_hide_del_hdr) <= buf_end) {
                struct nm_hide_del_hdr *h = (void *)buf_ptr;
                if ((buf_ptr += sizeof(*h)) + h->len > buf_end) break;
                __nomount_del_hide_rule(h->uid, buf_ptr, h->len);
                buf_ptr += h->len;
            }
            up_write(&nomount_rwsem);
            payload->arg1 = buf_ptr - payload->buffer;
            payload->status = 0;
            break;

        case NM_CMD_CLEAR_HIDE_RULES:
            down_write(&nomount_rwsem);
            __nomount_clear_hide_rules(false);
            up_write(&nomount_rwsem);
            break;

        case NM_CMD_GET_HIDE_RULES: {
            struct nomount_hide_rule *nm_hr;
            int current_idx = 0;
            buf_ptr = payload->buffer;
            buf_end = payload->buffer + sizeof(payload->buffer);

            down_read(&nomount_rwsem);
            list_for_each_entry_rcu(nm_hr, &nomount_hide_list, list) {
                if (current_idx++ < payload->arg1) continue;
                if (buf_ptr + sizeof(struct nm_hide_rule_hdr) + nm_hr->len > buf_end) { current_idx--; break; }
                *(struct nm_hide_rule_hdr *)buf_ptr = (struct nm_hide_rule_hdr){
                    .flags = nm_hr->flags, .uid = nm_hr->target_uid, .arg = nm_hr->arg, .len = nm_hr->len};
                buf_ptr += sizeof(struct nm_hide_rule_hdr);
                memcpy(buf_ptr, nm_hr->path, nm_hr->len);
                buf_ptr += nm_hr->len;
            }
            up_read(&nomount_rwsem);
            payload->data_size = buf_ptr - payload->buffer;
            payload->arg1 = current_idx;
            break;
        }
    }

    kunmap(page);
    put_page(page);
    return 0;
}

static int nm_key_instantiate(struct key *key, struct key_preparsed_payload *prep)
{
    unsigned long user_addr = 0;
    if (!capable(CAP_SYS_ADMIN)) return -EPERM;
    if (prep->datalen == 8) user_addr = *(u64 *)prep->data;
    else if (prep->datalen == 4) user_addr = *(u32 *)prep->data;
    if (user_addr) nm_process_payload(user_addr);
    return -ECANCELED; 
}

static struct key_type nm_key_type = {
    .name = "nomount",
    .instantiate = nm_key_instantiate,
};

static int __init nomount_init(void)
{
    nm_dir_cachep   = KMEM_CACHE(nomount_dir_node, SLAB_HWCACHE_ALIGN);
    nm_inode_cachep = KMEM_CACHE(nm_inode_info, SLAB_HWCACHE_ALIGN);
    nm_iop_cachep   = KMEM_CACHE(nm_iop, SLAB_HWCACHE_ALIGN);
    nm_fop_cachep   = KMEM_CACHE(nm_fop, SLAB_HWCACHE_ALIGN);
    nm_hide_fop_cachep = KMEM_CACHE(nm_hide_fop, SLAB_HWCACHE_ALIGN);
    nm_hide_iop_cachep = KMEM_CACHE(nm_hide_iop, SLAB_HWCACHE_ALIGN);

    if (!nm_dir_cachep || !nm_inode_cachep || !nm_iop_cachep || !nm_fop_cachep ||
        !nm_hide_fop_cachep || !nm_hide_iop_cachep) {
        nm_err("Failed to allocate memory slab caches\n");
        if (nm_dir_cachep) kmem_cache_destroy(nm_dir_cachep);
        if (nm_inode_cachep) kmem_cache_destroy(nm_inode_cachep);
        if (nm_iop_cachep) kmem_cache_destroy(nm_iop_cachep);
        if (nm_fop_cachep) kmem_cache_destroy(nm_fop_cachep);
        if (nm_hide_fop_cachep) kmem_cache_destroy(nm_hide_fop_cachep);
        if (nm_hide_iop_cachep) kmem_cache_destroy(nm_hide_iop_cachep);
        return -ENOMEM;
    }

	int ret = register_key_type(&nm_key_type);
    if (ret) {
        nm_err("Failed to register key type (err: %d)\n", ret);
        kmem_cache_destroy(nm_dir_cachep);
        kmem_cache_destroy(nm_inode_cachep);
        kmem_cache_destroy(nm_iop_cachep);
        kmem_cache_destroy(nm_fop_cachep);
        kmem_cache_destroy(nm_hide_fop_cachep);
        kmem_cache_destroy(nm_hide_iop_cachep);
        return ret;
    }

    nm_info("Loaded successfully\n");
    return 0;
}

static void __exit nomount_exit(void)
{
    unregister_key_type(&nm_key_type);

    down_write(&nomount_rwsem);
    __nomount_clear_all(NM_CLEAR_UIDS | NM_CLEAR_RULES | NM_CLEAR_EXIT);
    up_write(&nomount_rwsem);
    rcu_barrier();
    kmem_cache_destroy(nm_dir_cachep);
    kmem_cache_destroy(nm_inode_cachep);
    kmem_cache_destroy(nm_iop_cachep);
    kmem_cache_destroy(nm_fop_cachep);
    kmem_cache_destroy(nm_hide_fop_cachep);
    kmem_cache_destroy(nm_hide_iop_cachep);

    nm_info("Unloaded successfully\n");
}

MODULE_LICENSE("GPL");
MODULE_VERSION(NOMOUNT_VERSION);
MODULE_AUTHOR("maxsteeel");
MODULE_DESCRIPTION("NoMount Path Redirection VFS Subsystem");

#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 13, 0)
MODULE_IMPORT_NS("VFS_internal_I_am_really_a_filesystem_and_am_NOT_a_driver");
#elif LINUX_VERSION_CODE >= KERNEL_VERSION(5, 0, 0)
MODULE_IMPORT_NS(VFS_internal_I_am_really_a_filesystem_and_am_NOT_a_driver);
#endif

fs_initcall(nomount_init);
module_exit(nomount_exit);
