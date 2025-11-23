一個基於 Spring Boot 的高性能抽獎系統，支持高並發場景，使用 Redis 實現分布式鎖和緩存，確保數據一致性和系統穩定性。

## 🏗️ 系統架構

```
┌─────────────────────────────────────────────────────────────┐
│                        Client Layer                          │
│                  (Web / Mobile / API)                        │
└────────────────────────┬────────────────────────────────────┘
                         │
                         │ HTTP/REST
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                    Controller Layer                          │
│          (AuthController, UserController, AdminController)   │
└────────────────────────┬────────────────────────────────────┘
                         │
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                     Service Layer                            │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  LotteryService (抽獎核心邏輯)                        │   │
│  │  - 配額檢查與扣減                                     │   │
│  │  - 獎品選擇算法                                       │   │
│  │  - 庫存管理                                           │   │
│  └──────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  LotteryManagementService (管理功能)                  │   │
│  │  - 機率配置                                           │   │
│  │  - 緩存管理                                           │   │
│  └──────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  LotterySyncService (數據同步)                        │   │
│  │  - 配額同步                                           │   │
│  │  - 緊急恢復                                           │   │
│  └──────────────────────────────────────────────────────┘   │
└────────────────────────┬────────────────────────────────────┘
                         │
         ┌───────────────┴───────────────┐
         │                               │
         ▼                               ▼
┌──────────────────┐           ┌──────────────────┐
│   Redis Cache    │           │   MySQL DB       │
│                  │           │                  │
│ • 配額計數       │           │ • 活動數據       │
│ • 獎品庫存       │           │ • 獎品配置       │
│ • 機率配置       │           │ • 用戶配額       │
│ • 分布式鎖       │           │ • 中獎記錄       │
└──────────────────┘           └──────────────────┘
```

### 抽獎流程（無鎖設計）

```
User Request
     │
     ▼
┌─────────────────────┐
│  JWT Authentication │
│    (驗證 Token)     │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│  Check Active       │◄───── Redis Cache
│  (檢查活動狀態)     │       Double-Check Locking
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│  Deduct Event Quota │◄───── Redis AtomicLong
│  (扣減活動配額)     │       decrementAndGet()
│                     │       ⚠️ 順序關鍵！
└──────────┬──────────┘       (先扣活動配額)
           │
           │ 成功 ✓
           ▼
┌─────────────────────┐
│  Deduct User Quota  │◄───── Redis AtomicLong
│  (扣減用戶配額)     │       decrementAndGet()
│                     │       自動回滾機制
└──────────┬──────────┘
           │
           │ 成功 ✓
           ▼
┌─────────────────────┐
│  Load Prize Data    │◄───── Redis RMap
│  (載入獎品數據)     │       Lazy Init + 
└──────────┬──────────┘       Double-Check
           │
           ▼
┌─────────────────────┐
│  Select Prize       │◄───── 累積機率算法
│  (選擇獎品)         │       Math.random()
│                     │       0.0 ~ 1.0
└──────────┬──────────┘
           │
           │ 如果中獎
           ▼
┌─────────────────────┐
│  Deduct Stock       │◄───── Redis AtomicLong
│  (扣減獎品庫存)     │       decrementAndGet()
│                     │       CAS 操作
└──────────┬──────────┘
           │
           │ 庫存足夠 ✓
           ▼
┌─────────────────────┐
│  Save Record        │◄───── 異步保存 (可選)
│  (保存中獎記錄)     │       CompletableFuture
│                     │       不阻塞主流程
└──────────┬──────────┘
           │
           ▼
      Return Result
     (無需釋放鎖!)

關鍵特性:
✅ 無鎖設計 - 完全依賴原子操作
✅ 順序保證 - 先扣活動配額再扣用戶配額
✅ 自動回滾 - 失敗時自動還原配額
✅ 懶加載 - Double-Check Locking 初始化
✅ CAS 操作 - Compare-And-Set 避免競態
```




## 🛠️ 技術棧

### 後端框架
- **Spring Boot 3.4.0** - 應用框架
- **Spring Security** - 安全認證
- **Spring Data JPA** - 數據持久化
- **Spring Data Redis** - Redis 集成

### 數據存儲
- **MySQL 8.0+** - 關係型數據庫
- **Redis 7.0+** - 緩存和原子操作
- **Redisson 3.37.0** - Redis 客戶端（用於配置）

### 安全認證
- **JWT (jjwt 0.12.5)** - Token 生成和驗證
- **BCrypt** - 密碼加密

### 工具庫
- **Lombok** - 減少樣板代碼
- **Jackson** - JSON 序列化

### 測試
- **JUnit 5** - 單元測試
- **Spring Boot Test** - 集成測試
- **AssertJ** - 斷言庫

---

## 🚀 快速開始

### 前置要求

- Java 17+
- MySQL 8.0+
- Redis 7.0+
- Gradle 9.2+ (或使用自帶的 gradlew)

### 1. 克隆項目

```bash
git clone https://github.com/yourusername/lottery.git
cd lottery
```

### 2. 配置數據庫

創建 MySQL 數據庫：

```sql
CREATE DATABASE mydockerdb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

執行數據庫腳本：

```bash
mysql -u root -p mydockerdb < src/main/resources/static/schema.sql
mysql -u root -p mydockerdb < src/main/resources/static/seed.sql
```

### 3. 配置應用

編輯 `src/main/resources/application.properties`：

```properties
# 數據庫配置
spring.datasource.url=jdbc:mysql://localhost:3306/mydockerdb
spring.datasource.username=your_username
spring.datasource.password=your_password

# Redis 配置
redis.host=127.0.0.1
redis.port=6379
redis.password=
redis.database=0

# JWT 配置
jwt.secret=your-secret-key-at-least-32-characters-long
jwt.expiration=3600000
jwt.issuer=lottery-system
```

### 4. 啟動應用

```bash
# 使用 Gradle Wrapper
./gradlew bootRun

# 或者構建並運行
./gradlew build
java -jar build/libs/lottery-0.0.1-SNAPSHOT.jar
```

應用將在 `http://localhost:8080` 啟動

### 5. 測試 API

```bash
# 登錄獲取 Token
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "user",
    "password": "123456"
  }'

# 使用返回的 token 進行抽獎
curl -X POST http://localhost:8080/user/event/1/draw \
  -H "Authorization: Bearer {your_token}"
```

---

## ⚙️ 環境設定

### 開發環境

```properties
# application-dev.properties

# 開啟 SQL 日誌
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true

# 開發時自動創建表（慎用）
spring.jpa.hibernate.ddl-auto=validate

# Redis 本地配置
redis.host=localhost
redis.port=6379

# 日誌級別
logging.level.com.practice.lottery=DEBUG
```

### 生產環境

```properties
# application-prod.properties

# 關閉 SQL 日誌
spring.jpa.show-sql=false

# 數據庫連接池優化
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=10
spring.datasource.hikari.connection-timeout=30000
spring.datasource.hikari.idle-timeout=600000
spring.datasource.hikari.max-lifetime=1800000

# Redis 連接池優化
spring.data.redis.lettuce.pool.max-active=20
spring.data.redis.lettuce.pool.max-idle=10
spring.data.redis.lettuce.pool.min-idle=5

# Redisson 優化
spring.redis.redisson.connection-pool-size=64
spring.redis.redisson.connection-minimum-idle-size=10

# 日誌級別
logging.level.com.practice.lottery=INFO
```

### Docker 部署

創建 `docker-compose.yml`：

```yaml
version: '3.8'

services:
  mysql:
    image: mysql:8.0
    container_name: lottery-mysql
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: mydockerdb
      MYSQL_USER: test
      MYSQL_PASSWORD: test
    ports:
      - "3306:3306"
    volumes:
      - mysql-data:/var/lib/mysql
      - ./src/main/resources/static/schema.sql:/docker-entrypoint-initdb.d/1-schema.sql
      - ./src/main/resources/static/seed.sql:/docker-entrypoint-initdb.d/2-seed.sql

  redis:
    image: redis:7.0-alpine
    container_name: lottery-redis
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data

  app:
    build: .
    container_name: lottery-app
    depends_on:
      - mysql
      - redis
    ports:
      - "8080:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/mydockerdb
      SPRING_DATASOURCE_USERNAME: test
      SPRING_DATASOURCE_PASSWORD: test
      REDIS_HOST: redis
      REDIS_PORT: 6379

volumes:
  mysql-data:
  redis-data:
```

啟動：

```bash
docker-compose up -d
```

---

## 💾 資料庫設定

### 數據庫結構

系統使用 5 個主要表：

#### 1. users - 用戶表
```sql
CREATE TABLE `users` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(100) NOT NULL,
  `password` varchar(100) DEFAULT NULL,
  `role` varchar(100) DEFAULT NULL,
  `version` bigint DEFAULT 0,
  `created_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `user_unique` (`username`)
);
```

#### 2. lottery_event - 抽獎活動表
```sql
CREATE TABLE `lottery_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(255) DEFAULT NULL,
  `is_active` tinyint(1) DEFAULT '0',
  `setting_amount` int DEFAULT NULL,
  `remain_amount` int DEFAULT NULL,
  `version` bigint DEFAULT 0,
  `created_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
);
```

#### 3. lottery_prize - 獎品表
```sql
CREATE TABLE `lottery_prize` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `lottery_event_id` bigint DEFAULT NULL,
  `name` varchar(255) DEFAULT NULL,
  `rate` decimal(3,2) DEFAULT NULL,
  `amount` int DEFAULT NULL,
  `version` bigint DEFAULT 0,
  `created_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_event_id` (`lottery_event_id`)
);
```

#### 4. user_lottery_quota - 用戶配額表
```sql
CREATE TABLE `user_lottery_quota` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `uid` bigint DEFAULT NULL,
  `lottery_event_id` bigint DEFAULT NULL,
  `draw_quota` int DEFAULT '0',
  `version` bigint DEFAULT 0,
  `created_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_uid_event` (`uid`, `lottery_event_id`)
);
```

#### 5. win_record - 中獎記錄表
```sql
CREATE TABLE `win_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `uid` bigint DEFAULT NULL,
  `lottery_event_id` bigint DEFAULT NULL,
  `draw_prize_id` bigint DEFAULT NULL,
  `remain_prize_amount` int DEFAULT NULL,
  `created_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_uid` (`uid`),
  KEY `idx_event_id` (`lottery_event_id`)
);
```

### 初始數據

系統預設兩個測試用戶：

| 用戶名 | 密碼 | 角色 |
|--------|------|------|
| admin | 123456 | ADMIN |
| user | 123456 | USER |

## 📡 API 文檔

完整的 API 文檔請參考 [API Documentation](docs/API.md)

### 快速參考

#### 認證 API

| 方法 | 端點 | 描述 | 認證 |
|------|------|------|------|
| POST | `/auth/login` | 用戶登錄 | ❌ |

#### 用戶 API

| 方法 | 端點 | 描述 | 認證 |
|------|------|------|------|
| GET | `/user/test` | 測試認證 | ✅ USER |
| POST | `/user/event/{eventId}/draw` | 參與抽獎 | ✅ USER |
| POST | `/user/event/{eventId}/draw?isKeepResult=true` | 參與抽獎(保存記錄) | ✅ USER |
| GET | `/user/my-records` | 查看中獎記錄 | ✅ USER |

#### 管理員 API

| 方法 | 端點 | 描述 | 認證 |
|------|------|------|------|
| GET | `/admin/events` | 獲取所有活動 | ✅ ADMIN |
| GET | `/admin/event/{eventId}/status` | 獲取活動狀態 | ✅ ADMIN |
| PUT | `/admin/update` | 批量更新配置 | ✅ ADMIN |
| PUT | `/admin/event/{eventId}/prize/{prizeId}/rate` | 更新單個獎品機率 | ✅ ADMIN |
| POST | `/admin/event/{eventId}/refresh-cache` | 刷新緩存 | ✅ ADMIN |
| DELETE | `/admin/event/{eventId}/clear-cache` | 清除緩存 | ✅ ADMIN |

### API 使用示例

#### 1. 登錄

```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "user",
    "password": "123456"
  }'
```

響應：
```json
{
  "code": 200,
  "message": "Login successful",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "token_type": "Bearer",
    "user_id": 2,
    "username": "user",
    "role": "USER"
  },
  "timestamp": "2025-11-23T10:30:00"
}
```

#### 2. 參與抽獎

```bash
# 快速抽獎
curl -X POST http://localhost:8080/user/event/1/draw \
  -H "Authorization: Bearer {your_token}"


#### 3. 查看中獎記錄

```bash
curl -X GET http://localhost:8080/user/my-records \
  -H "Authorization: Bearer {your_token}"
```

響應：
```json
{
  "code": 200,
  "message": "Win records retrieved successfully",
  "data": [
    {
      "id": 1,
      "lottery_event_id": 1,
      "event_name": "Spring Festival Lottery",
      "uid": 2,
      "draw_prize_id": 1,
      "prize_name": "FirstPrize",
      "remain_prize_amount": 95,
      "created_time": "2025-11-23T10:31:00"
    }
  ],
  "timestamp": "2025-11-23T10:32:00"
}
```

#### 4. 管理員更新機率

```bash
curl -X PUT http://localhost:8080/admin/update \
  -H "Authorization: Bearer {admin_token}" \
  -H "Content-Type: application/json" \
  -d '{
    "event_id": 1,
    "is_active": true,
    "rate_update_list": [
      {
        "id": 1,
        "rate": 0.08
      }
    ]
  }'
```

---


## 💾 資料庫設定

### 數據庫結構

系統使用 5 個主要表：

#### 1. users - 用戶表
```sql
CREATE TABLE `users` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(100) NOT NULL,
  `password` varchar(100) DEFAULT NULL,
  `role` varchar(100) DEFAULT NULL,
  `created_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `user_unique` (`username`)
);
```

#### 2. lottery_event - 抽獎活動表
```sql
CREATE TABLE `lottery_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(255) DEFAULT NULL,
  `is_active` tinyint(1) DEFAULT '0',
  `setting_amount` int DEFAULT NULL,
  `remain_amount` int DEFAULT NULL,
  `created_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
);
```

#### 3. lottery_prize - 獎品表
```sql
CREATE TABLE `lottery_prize` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `lottery_event_id` bigint DEFAULT NULL,
  `name` varchar(255) DEFAULT NULL,
  `rate` decimal(3,2) DEFAULT NULL,
  `amount` int DEFAULT NULL,
  `created_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_event_id` (`lottery_event_id`)
);
```

#### 4. user_lottery_quota - 用戶配額表
```sql
CREATE TABLE `user_lottery_quota` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `uid` bigint DEFAULT NULL,
  `lottery_event_id` bigint DEFAULT NULL,
  `draw_quota` int DEFAULT '0',
  `created_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_uid_event` (`uid`, `lottery_event_id`)
);
```

#### 5. win_record - 中獎記錄表
```sql
CREATE TABLE `win_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `uid` bigint DEFAULT NULL,
  `lottery_event_id` bigint DEFAULT NULL,
  `draw_prize_id` bigint DEFAULT NULL,
  `remain_prize_amount` int DEFAULT NULL,
  `created_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_uid` (`uid`),
  KEY `idx_event_id` (`lottery_event_id`)
);
```
---

## 📁 項目結構

```
lottery/
├── src/
│   ├── main/
│   │   ├── java/com/practice/lottery/
│   │   │   ├── config/
│   │   │   │   ├── security/          # 安全配置
│   │   │   │   │   ├── JwtAuthFilter.java
│   │   │   │   │   ├── JwtUtil.java
│   │   │   │   │   ├── JwtProperties.java
│   │   │   │   │   └── SecurityConfig.java
│   │   │   │   ├── AsyncConfig.java   # 異步配置
│   │   │   │   └── RedissonConfig.java # Redisson 配置
│   │   │   │
│   │   │   ├── controller/
│   │   │   │   ├── request/           # 請求 DTO
│   │   │   │   ├── response/          # 響應 DTO
│   │   │   │   ├── AuthController.java
│   │   │   │   ├── UserController.java
│   │   │   │   └── AdminController.java
│   │   │   │
│   │   │   ├── service/
│   │   │   │   ├── LotteryService.java          # 抽獎核心服務
│   │   │   │   ├── LotteryManagementService.java # 管理服務
│   │   │   │   ├── LotterySyncService.java      # 同步服務
│   │   │   │   └── WinRecordService.java        # 中獎記錄服務
│   │   │   │
│   │   │   ├── dao/
│   │   │   │   ├── entity/            # 實體類
│   │   │   │   │   ├── User.java
│   │   │   │   │   ├── LotteryEvent.java
│   │   │   │   │   ├── LotteryPrize.java
│   │   │   │   │   ├── UserLotteryQuota.java
│   │   │   │   │   └── WinRecord.java
│   │   │   │   └── repository/        # Repository 接口
│   │   │   │
│   │   │   ├── dto/
│   │   │   │   └── ApiResponse.java   # 統一響應格式
│   │   │   │
│   │   │   ├── exception/             # 異常處理
│   │   │   │   ├── LotteryException.java
│   │   │   │   ├── AuthenticationException.java
│   │   │   │   └── GlobalExceptionHandler.java
│   │   │   │
│   │   │   └── LotteryApplication.java
│   │   │
│   │   └── resources/
│   │       ├── application.properties
│   │       └── static/
│   │           ├── schema.sql         # 數據庫結構