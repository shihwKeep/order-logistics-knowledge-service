package com.xjjk.knowledge.knowledgebase.persistence;

import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import com.xjjk.knowledge.knowledgebase.domain.KnowledgeBase;
import com.xjjk.knowledge.knowledgebase.domain.KnowledgeBaseStatus;
import org.springframework.stereotype.Repository;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/** 基于 MyBatis 的知识库存储实现。 */
@Repository
public class MybatisKnowledgeBaseRepository implements KnowledgeBaseRepository {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final KnowledgeBaseMapper mapper;

    public MybatisKnowledgeBaseRepository(KnowledgeBaseMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public KnowledgeBase create(
            long tenantId,
            long actorUserId,
            String name,
            String description) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setTenantId(tenantId);
        entity.setName(name);
        entity.setDescription(description);
        entity.setStatus(KnowledgeBaseStatus.ENABLED.name());
        entity.setCreatedBy(actorUserId);
        entity.setUpdatedBy(actorUserId);
        try {
            mapper.insert(entity);
        } catch (RuntimeException exception) {
            if (isDuplicateKey(exception)) {
                throw new BusinessException(ApiErrorCode.KNOWLEDGE_BASE_NAME_CONFLICT);
            }
            throw exception;
        }
        return requireExisting(tenantId, entity.getId());
    }

    @Override
    public Optional<KnowledgeBase> findById(long tenantId, long knowledgeBaseId) {
        return Optional.ofNullable(mapper.findById(tenantId, knowledgeBaseId))
                .map(this::toDomain);
    }

    @Override
    public List<KnowledgeBase> list(long tenantId) {
        return mapper.list(tenantId).stream().map(this::toDomain).toList();
    }

    @Override
    public KnowledgeBase update(
            long tenantId,
            long knowledgeBaseId,
            int expectedVersion,
            String name,
            String description,
            long actorUserId) {
        requireExisting(tenantId, knowledgeBaseId);
        try {
            int affected = mapper.update(
                    tenantId,
                    knowledgeBaseId,
                    expectedVersion,
                    name,
                    description,
                    actorUserId);
            requireUpdated(affected);
        } catch (RuntimeException exception) {
            if (isDuplicateKey(exception)) {
                throw new BusinessException(ApiErrorCode.KNOWLEDGE_BASE_NAME_CONFLICT);
            }
            throw exception;
        }
        return requireExisting(tenantId, knowledgeBaseId);
    }

    @Override
    public KnowledgeBase setStatus(
            long tenantId,
            long knowledgeBaseId,
            KnowledgeBaseStatus status,
            long actorUserId) {
        KnowledgeBase current = requireExisting(tenantId, knowledgeBaseId);
        int affected = mapper.setStatus(
                tenantId,
                knowledgeBaseId,
                current.rowVersion(),
                status.name(),
                actorUserId);
        requireUpdated(affected);
        return requireExisting(tenantId, knowledgeBaseId);
    }

    @Override
    public void softDelete(long tenantId, long knowledgeBaseId, long actorUserId) {
        KnowledgeBase current = requireExisting(tenantId, knowledgeBaseId);
        int affected = mapper.softDelete(
                tenantId,
                knowledgeBaseId,
                current.rowVersion(),
                actorUserId);
        requireUpdated(affected);
    }

    private KnowledgeBase requireExisting(long tenantId, long knowledgeBaseId) {
        return findById(tenantId, knowledgeBaseId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
    }

    private void requireUpdated(int affected) {
        if (affected != 1) {
            throw new BusinessException(ApiErrorCode.KNOWLEDGE_BASE_VERSION_CONFLICT);
        }
    }

    private boolean isDuplicateKey(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLIntegrityConstraintViolationException sqlException
                    && sqlException.getErrorCode() == 1062) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private KnowledgeBase toDomain(KnowledgeBaseEntity entity) {
        return new KnowledgeBase(
                entity.getId(),
                entity.getTenantId(),
                entity.getName(),
                entity.getDescription(),
                KnowledgeBaseStatus.valueOf(entity.getStatus()),
                entity.getRowVersion(),
                entity.getCreatedAt().atZone(BUSINESS_ZONE).toOffsetDateTime(),
                entity.getUpdatedAt().atZone(BUSINESS_ZONE).toOffsetDateTime());
    }
}
