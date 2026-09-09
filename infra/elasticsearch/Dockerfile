ARG ELASTIC_VERSION=8.15.3
FROM docker.elastic.co/elasticsearch/elasticsearch:${ELASTIC_VERSION}

ARG ELASTIC_VERSION
# IK 必须与 Elasticsearch 版本严格一致；镜像构建阶段失败比运行后静默退化更安全。
RUN bin/elasticsearch-plugin install --batch \
    "https://get.infini.cloud/elasticsearch/analysis-ik/${ELASTIC_VERSION}"
