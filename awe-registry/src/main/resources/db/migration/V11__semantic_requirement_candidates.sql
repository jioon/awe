-- 以确切语义发布查找已授权实现；不扫描或截断无关的全域 Function 目录。
-- 仅添加索引，不改写既有 Release、依赖清单、Hash 或观测。
CREATE INDEX idx_relation_exact_release_candidate ON awe_release_relation(target_release_id,release_id)
 WHERE target_release_id IS NOT NULL;
