# =============================================================================
# Quant Server Docker Image
# =============================================================================

FROM eclipse-temurin:17-jre-alpine

# 安装必要的工具
RUN apk add --no-cache curl tzdata \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone \
    && apk del tzdata

# 创建应用用户
RUN addgroup -S quant && adduser -S quant -G quant

# 设置工作目录
WORKDIR /app

# 创建必要的目录
RUN mkdir -p /app/lib /app/config /app/logs /app/data \
    && chown -R quant:quant /app

# 复制JAR文件（需要在构建时提供）
COPY --chown=quant:quant build/deploy/lib/quant-server.jar /app/lib/

# 复制配置文件
COPY --chown=quant:quant build/deploy/config/ /app/config/

# 复制启动脚本
COPY --chown=quant:quant src/main/scripts/start.sh /app/bin/
RUN chmod +x /app/bin/start.sh

# 切换到应用用户
USER quant

# 暴露端口
EXPOSE 8080

# 健康检查
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

# 启动命令
ENTRYPOINT ["/app/bin/start.sh"]
CMD ["start"]
