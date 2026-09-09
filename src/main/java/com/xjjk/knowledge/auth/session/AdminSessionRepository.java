package com.xjjk.knowledge.auth.session;

import java.util.Optional;

/** 不透明管理会话存储接口。 */
public interface AdminSessionRepository {

    String create(AdminSession session);

    Optional<AdminSession> find(String browserToken);

    void save(String browserToken, AdminSession session);

    void delete(String browserToken);
}
