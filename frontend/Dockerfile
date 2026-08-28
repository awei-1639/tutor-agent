FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci --include=optional
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
# 健康检查经 nginx 代理探测后端 /api/readyz，需要 wget；nginx alpine 镜像不自带。
RUN apk add --no-cache wget
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
