package com.example.aicodebackend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 轻量数据库结构自愈
 * <p>
 * 按需补齐本次功能新增的 app 表字段（{@code deploy_status} / {@code deploy_error} / {@code deploy_operator_id}
 * 与对应的状态索引），并把历史数据的空状态归一化为 {@code idle}。
 * <p>
 * 之所以放在启动期自动执行，而不是要求手工跑 SQL：
 * <ul>
 *     <li>项目本身没有引入 Flyway/Liquibase 之类的迁移工具，加一整套框架成本过高；</li>
 *     <li>改动是"加列 + 索引 + 数据归一"，全部幂等（存在即跳过），可以安全地重复执行；</li>
 *     <li>部署状态是异步部署接口能否工作的前提，缺少字段会让接口直接报错，启动期修复最省事。</li>
 * </ul>
 * 生产环境如果已有正式的迁移流程，可以删除该组件、改用迁移脚本。
 */
@Slf4j
@Component
public class AppSchemaInitializer implements ApplicationRunner {

    private static final String TABLE_NAME = "app";

    private final DataSource dataSource;

    public AppSchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            if (!tableExists(connection, TABLE_NAME)) {
                log.warn("未找到 {} 表，跳过部署状态字段初始化", TABLE_NAME);
                return;
            }
            addColumnIfMissing(connection, "deploy_status",
                    "varchar(32) null comment '部署状态：idle/deploying/ready/failed' after `deployed_time`");
            addColumnIfMissing(connection, "deploy_error",
                    "varchar(1024) null comment '最近一次部署失败原因' after `deploy_status`");
            addColumnIfMissing(connection, "deploy_operator_id",
                    "bigint null comment '最近一次部署发起人 id' after `deploy_error`");
            addIndexIfMissing(connection, "idx_deploy_status", "(`deploy_status`)");
            normalizeLegacyStatus(connection);
        } catch (Exception e) {
            // 初始化失败不阻塞启动：异步部署接口会在缺少字段时报明确错误，而不是整个应用起不来
            log.error("部署状态字段初始化失败，异步部署接口可能不可用", e);
        }
    }

    /**
     * 判断表是否存在
     */
    private boolean tableExists(Connection connection, String tableName) throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getTables(connection.getCatalog(), null, tableName, new String[]{"TABLE"})) {
            if (rs.next()) {
                return true;
            }
        }
        // MySQL 在部分驱动/大小写配置下会以大写返回表名，这里再兜底查一次
        try (ResultSet rs = metaData.getTables(connection.getCatalog(), null, tableName.toUpperCase(), new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    /**
     * 字段不存在时新增
     */
    private void addColumnIfMissing(Connection connection, String columnName, String definition) throws Exception {
        if (columnExists(connection, columnName)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE `" + TABLE_NAME + "` ADD COLUMN `" + columnName + "` " + definition);
            log.info("已为 app 表新增字段: {}", columnName);
        }
    }

    /**
     * 索引不存在时新增
     */
    private void addIndexIfMissing(Connection connection, String indexName, String columns) throws Exception {
        if (indexExists(connection, indexName)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE `" + TABLE_NAME + "` ADD INDEX `" + indexName + "` " + columns);
            log.info("已为 app 表新增索引: {}", indexName);
        }
    }

    /**
     * 字段是否存在
     */
    private boolean columnExists(Connection connection, String columnName) throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getColumns(connection.getCatalog(), null, TABLE_NAME, columnName)) {
            return rs.next();
        }
    }

    /**
     * 索引是否存在
     */
    private boolean indexExists(Connection connection, String indexName) throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getIndexInfo(connection.getCatalog(), null, TABLE_NAME, false, false)) {
            while (rs.next()) {
                if (indexName.equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 历史数据归一化：已有 deployKey 的视为部署完成，其余视为空闲
     */
    private void normalizeLegacyStatus(Connection connection) throws Exception {
        int updated;
        try (Statement statement = connection.createStatement()) {
            updated = statement.executeUpdate("UPDATE `" + TABLE_NAME + "` SET `deploy_status` = 'ready' "
                    + "WHERE (`deploy_status` IS NULL OR `deploy_status` = '') AND `deploy_key` IS NOT NULL");
        }
        try (Statement statement = connection.createStatement()) {
            updated += statement.executeUpdate("UPDATE `" + TABLE_NAME + "` SET `deploy_status` = 'idle' "
                    + "WHERE (`deploy_status` IS NULL OR `deploy_status` = '') AND `deploy_key` IS NULL");
        }
        if (updated > 0) {
            log.info("已归一化历史部署状态，影响行数: {}", updated);
        }
    }
}
