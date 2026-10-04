# 数据库初始化
# @author: Turner
# @date: 2026-09-27

-- 创建数据库
create database if not exists ai_code_db default character set utf8mb4 collate utf8mb4_general_ci;

-- 使用数据库
use ai_code_db;

-- 用户表
create table if not exists user
(
    id            bigint auto_increment comment 'id' primary key,
    user_account  varchar(256)                           not null comment '账号',
    user_password varchar(512)                           not null comment '密码',
    username      varchar(256)                           null comment '用户昵称',
    user_avatar   varchar(1024)                          null comment '用户头像',
    user_profile  varchar(512)                           null comment '用户简介',
    user_role     varchar(256) default 'user'            not null comment '用户角色：user/admin',
    edit_time     datetime     default CURRENT_TIMESTAMP not null comment '编辑时间',
    create_time   datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    is_delete     tinyint      default 0                 not null comment '是否删除',
    UNIQUE KEY uk_user_account (user_account),
    INDEX idx_username (username)
) comment '用户' collate = utf8mb4_general_ci;
-- 应用表
create table app
(
    id            bigint auto_increment comment 'id' primary key,
    app_name      varchar(256)                       null comment '应用名称',
    cover         varchar(512)                       null comment '应用封面',
    init_prompt   text                               null comment '应用初始化的 prompt',
    code_gen_type varchar(64)                        null comment '代码生成类型（枚举）',
    deploy_key    varchar(64)                        null comment '部署标识',
    deployed_time datetime                           null comment '部署时间',
    priority      int      default 0                 not null comment '优先级',
    user_id       bigint                             not null comment '创建用户id',
    edit_time     datetime default CURRENT_TIMESTAMP not null comment '编辑时间',
    create_time   datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time   datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    is_delete     tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_deploy_key (deploy_key), -- 确保部署标识唯一
    INDEX idx_app_name (app_name),         -- 提升基于应用名称的查询性能
    INDEX idx_user_id (user_id)            -- 提升基于用户 ID 的查询性能
) comment '应用' collate = utf8mb4_general_ci;
-- 对话历史表
create table chat_history
(
    id           bigint auto_increment comment 'id' primary key,
    message      text                               not null comment '消息',
    message_type varchar(32)                        not null comment '消息类型：user/ai/error',
    app_id       bigint                             not null comment '应用id',
    user_id      bigint                             not null comment '创建用户id',
    create_time  datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time  datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    is_delete    tinyint  default 0                 not null comment '是否删除',
    INDEX idx_app_id (app_id),                         -- 提升基于应用的查询性能
    INDEX idx_create_time (create_time),               -- 提升基于时间的查询性能
    INDEX idx_app_id_create_time (app_id, create_time) -- 游标查询核心索引
) comment '对话历史' collate = utf8mb4_general_ci;
-- 添加父消息id字段，用于上下文关联
alter table chat_history
    add column parent_id bigint null comment '父消息id（用于上下文关联）';
-- 应用表添加 AI 模型类型字段，用于记录该应用使用的模型
alter table app
    add column ai_model_type varchar(64) default 'deepseek-flash' null comment 'AI 模型类型（枚举）' after code_gen_type;
