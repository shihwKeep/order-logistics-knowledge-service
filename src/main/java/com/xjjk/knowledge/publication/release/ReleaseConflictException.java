package com.xjjk.knowledge.publication.release;

/** Release 准备期间活动基线或内容清单发生变化，拒绝覆盖当前线上版本。 */
public class ReleaseConflictException extends RuntimeException {
    public ReleaseConflictException() {
        super("Release 基线或内容清单已经变化");
    }
}
