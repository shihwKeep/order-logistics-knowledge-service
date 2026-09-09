package com.xjjk.knowledge.publication;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class PublicationTransactionContractTest {
    @Test
    void publicationUsesReadCommittedAndLockingIdempotencyRead() throws Exception {
        for (String methodName : new String[] {"publish", "rollback", "disable"}) {
            Method method = java.util.Arrays.stream(PublicationService.class.getMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst().orElseThrow();
            assertThat(method.getAnnotation(Transactional.class).isolation())
                    .isEqualTo(Isolation.READ_COMMITTED);
        }
        Select select = PublicationMapper.class.getMethod(
                        "findRecordForUpdate", long.class, String.class)
                .getAnnotation(Select.class);
        assertThat(String.join(" ", select.value()).replaceAll("\\s+", " "))
                .contains("FOR UPDATE");
    }
}
