package com.xjjk.knowledge.publication.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class ReleaseTransactionContractTest {
    @Test
    void releaseCreationAndActivationUseTransactionsWithKnowledgeBaseLocking() throws Exception {
        for (String methodName : new String[] {
                "create", "publishDocument", "rollbackDocument", "disableDocument", "rollback"
        }) {
            Method method = Arrays.stream(ReleaseService.class.getMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst().orElseThrow();
            assertThat(method.getAnnotation(Transactional.class))
                    .as("ReleaseService.%s 必须声明事务边界", methodName)
                    .isNotNull();
        }

        Method activate = MybatisReleaseRepository.class.getMethod(
                "activate", KnowledgeRelease.class, ReleaseTaskLease.class);
        assertThat(activate.getAnnotation(Transactional.class))
                .as("Release 原子激活必须声明事务边界")
                .isNotNull();

        Select baselineLock = ReleaseMapper.class.getMethod(
                        "lockBaseline", long.class, long.class)
                .getAnnotation(Select.class);
        assertThat(String.join(" ", baselineLock.value()).replaceAll("\\s+", " "))
                .contains("FOR UPDATE");
    }
}
