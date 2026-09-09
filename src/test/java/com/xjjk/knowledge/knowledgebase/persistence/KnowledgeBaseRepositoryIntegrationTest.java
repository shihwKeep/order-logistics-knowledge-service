package com.xjjk.knowledge.knowledgebase.persistence;

import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import com.xjjk.knowledge.knowledgebase.domain.KnowledgeBase;
import com.xjjk.knowledge.knowledgebase.domain.KnowledgeBaseStatus;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class KnowledgeBaseRepositoryIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("order_logistics_knowledge")
            .withUsername("knowledge")
            .withPassword("knowledge");

    private static SqlSession sqlSession;
    private static KnowledgeBaseRepository repository;

    @BeforeAll
    static void setUpRepository() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load()
                .migrate();

        PooledDataSource dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver",
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword());
        Environment environment = new Environment(
                "test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(KnowledgeBaseMapper.class);
        SqlSessionFactory sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        sqlSession = sessionFactory.openSession(true);
        repository = new MybatisKnowledgeBaseRepository(
                sqlSession.getMapper(KnowledgeBaseMapper.class));
    }

    @AfterAll
    static void closeSession() {
        if (sqlSession != null) {
            sqlSession.close();
        }
    }

    @Test
    void everyReadIsRestrictedToTenantAndActiveRows() {
        KnowledgeBase created = repository.create(1L, 10567L, "售后规则", "退款与换货政策");
        repository.create(2L, 20001L, "物流规则", "配送时效");

        assertThat(repository.findById(1L, created.id())).isPresent();
        assertThat(repository.findById(2L, created.id())).isEmpty();
        assertThat(repository.list(1L))
                .extracting(KnowledgeBase::tenantId)
                .containsOnly(1L);

        repository.softDelete(1L, created.id(), 10567L);

        assertThat(repository.findById(1L, created.id())).isEmpty();
        assertThat(repository.list(1L))
                .extracting(KnowledgeBase::id)
                .doesNotContain(created.id());
    }

    @Test
    void staleVersionIsRejectedAndVersionAdvancesAtomically() {
        KnowledgeBase created = repository.create(11L, 10567L, "订单规则", "订单状态说明");

        KnowledgeBase updated = repository.update(
                11L, created.id(), 0, "订单规则新版", "更新说明", 10567L);

        assertThat(updated.rowVersion()).isEqualTo(1);
        assertThatThrownBy(() -> repository.update(
                11L, created.id(), 0, "过期写入", "不应成功", 10567L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ApiErrorCode.KNOWLEDGE_BASE_VERSION_CONFLICT));
    }

    @Test
    void duplicateNameInSameTenantIsRejectedButOtherTenantCanReuseIt() {
        repository.create(21L, 10567L, "公共规则", "租户一");
        repository.create(22L, 20001L, "公共规则", "租户二");

        assertThatThrownBy(() -> repository.create(21L, 10567L, "公共规则", "重复"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ApiErrorCode.KNOWLEDGE_BASE_NAME_CONFLICT));
    }

    @Test
    void statusChangeRemainsTenantScoped() {
        KnowledgeBase created = repository.create(31L, 10567L, "客户规则", "客户分级");

        KnowledgeBase disabled = repository.setStatus(
                31L, created.id(), KnowledgeBaseStatus.DISABLED, 10567L);

        assertThat(disabled.status()).isEqualTo(KnowledgeBaseStatus.DISABLED);
        assertThat(repository.findById(32L, created.id())).isEmpty();
    }
}
