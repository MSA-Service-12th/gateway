# Loopang Gateway

Spring Cloud Gateway 기반 API Gateway 서버. MSA 아키텍처의 단일 진입점 역할을 합니다.

## 기술 스택

- Java 21, Spring Boot 3.5.13, Spring Cloud 2025.0.1
- Spring Cloud Gateway (Reactive, WebFlux 기반)
- Spring Security + OAuth2 Resource Server (Keycloak JWT 검증)
- Eureka Client (서비스 디스커버리)
- Actuator (health, info 엔드포인트)
- Lombok
- Gradle 8.14.4
- Docker 멀티스테이지 빌드 (eclipse-temurin:21-jdk → 21-jre)

## 아키텍처

```
Client → Route53 (HTTPS)
       → ALB (HTTPS 종료, HTTP→HTTPS 리디렉션)
       → Target Group (HTTP:18080, /actuator/health)
       → Gateway EC2 (t3.micro, 2~5대 Auto Scaling)
       → 하위 마이크로서비스들
                ↕
          GCP Kafka (서비스 간 비동기 통신)
```

- EC2 + ASG 방식 (ECS 미사용)
- Gateway는 stateless (JWT), Config 서버로 환경 통일
- Gateway는 Kafka 직접 사용 안 함 (MSA 원칙) → 하위 서비스에서 연결

## 라우팅

| 경로 | 서비스 | Eureka 이름 |
|---|---|---|
| `/api/users/**` | user-service | USER-SERVICE |
| `/api/orders/**` | order-service | ORDER-SERVICE |
| `/api/hubs/**` | hub-service | HUB-SERVICE |
| `/api/hub-inventories/**` | hub-service | HUB-SERVICE |
| `/internal/hub-inventories/**` | hub-service | HUB-SERVICE |
| `/api/hub-routes/**` | route-service | ROUTE-SERVICE |
| `/api/companies/**` | company-service | COMPANY-SERVICE |
| `/api/items/**` | item-service | ITEM-SERVICE |
| `/api/deliveries/**` | delivery-service | DELIVERY-SERVICE |

## 인증/인가

### UserContextFilter
Gateway에서 JWT 토큰을 파싱하여 유저 정보를 헤더로 변환합니다.

| JWT 클레임 | 변환 헤더 |
|---|---|
| sub | X-User-UUID |
| email | X-User-Email |
| name | X-User-Name (URL 인코딩) |
| slack_id | X-User-Slack-Id |
| role | X-User-Role |
| is_enabled | X-User-Enabled |

- 인증 안 된 요청은 헤더 없이 통과 (서비스에서 null 처리)
- 기존 유저 헤더 제거 후 재설정 (위변조 방지)

### Keycloak
- issuer-uri: `http://localhost:13300/realms/my-realm`
- JWT 검증: `oauth2ResourceServer.jwt()`

## 인프라 구성 요약

| 리소스 | 설명 |
|---|---|
| ECR | Docker 이미지 저장소 |
| EC2 | t3.micro, Amazon Linux 2023 |
| ALB | HTTP→HTTPS 리디렉션, HTTPS→TG 전달 |
| Target Group | HTTP:18080, health: /actuator/health |
| Auto Scaling Group | min=2, max=5, CPU 70% Target Tracking |
| Launch Template | User Data로 Docker 자동 실행, IAM Instance Profile 포함 (v2) |
| IAM Role | `loopang-gateway-ec2-role` (ECR ReadOnly) |
| IAM Instance Profile | `loopang-gateway-ec2-profile` → Launch Template 연결 |
| Route53 | ALB Alias A Record |
| ACM | SSL/TLS 인증서 |

## Auto Scaling 설정

- 원하는 용량: 2대 / 최소: 2대 / 최대: 5대
- 크기 조정 정책: Target Tracking - 평균 CPU 사용률 70%
- 가용 영역: 4개 AZ
- ELB 헬스체크 활성화 (유예 기간: 300초)

## 배포 방법 (수동 Docker 배포)

### 1. 로컬 빌드 & ECR 푸시 (Apple Silicon 맥북)

```bash
# ECR 로그인
aws ecr get-login-password --region ap-northeast-2 | docker login --username AWS --password-stdin <ECR_URI>

# AMD64 크로스 빌드 & 푸시
docker buildx build --platform linux/amd64 --network=host -t <ECR_URI>:latest --push .
```

### 2. EC2 배포

```bash
# ECR 로그인 & pull
aws ecr get-login-password --region ap-northeast-2 | sudo docker login --username AWS --password-stdin <ECR_URI>
sudo docker pull <ECR_URI>:latest

# 실행
sudo docker run -d --name gateway -p 18080:18080 -e TZ=Asia/Seoul --restart unless-stopped <ECR_URI>:latest

# 헬스체크
curl http://localhost:18080/actuator/health
```

## 인프라 복구 전략

CloudFormation 템플릿으로 인프라 삭제 후 한 방에 복구 가능.

**삭제 시 유지할 리소스 (저비용):** AMI, Route53 Hosted Zone, ACM 인증서, ECR 이미지

**복구 소요 시간:** 약 15~20분

## Blue/Green 배포 흐름 (GitHub Actions 자동화 완료)

1. Docker 이미지 빌드 → ECR 푸시 (latest + commit SHA)
2. 현재 Blue 환경 정보 수집 (Listener ARN, Blue TG ARN, Blue ASG)
3. Green Target Group 생성 (`lp-gw-green-tg-{run_number}`)
4. Green ASG 생성 (`lp-gw-green-asg-{run_number}`, min=2, max=5)
5. Green TG를 ALB에 임시 연결 (Blue:100, Green:1 가중치) — 헬스체크 활성화
6. Green 인스턴스 헬스체크 대기 (최대 20분, 2개 이상 healthy)
7. ALB HTTPS:443 리스너 → Green TG로 전환 (무중단)
8. https://loopang.site/actuator/health 최종 검증
9. Blue ASG 축소(0) → 삭제, Blue TG 삭제
10. 실패 시 자동 롤백: ALB → Blue 복원, Green 리소스 삭제

## GitHub Actions CI/CD

- 워크플로우: `.github/workflows/ci-cd.yml`
- CI 트리거: develop, main push + PR (빌드 + 테스트)
- CD 트리거: main push only (Blue/Green 배포)
- IAM 사용자: `loopang-github-actions` (ECR PowerUser + EC2/ASG/ELB FullAccess + PassRoleForGateway)
- GitHub Secrets: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`

## 인프라 구축 진행 상황

1. ✅ ECR (이미지 저장소)
2. ✅ EC2 수동 배포 (단일 실행 검증)
3. ✅ Elastic IP 연결
4. ✅ AMI 생성
5. ✅ Launch Template 생성
6. ✅ ALB + Target Group 생성
7. ✅ Auto Scaling Group 생성
8. ✅ Route53 설정 + 도메인 연결
9. ✅ GitHub Actions CI/CD
10. ✅ Blue/Green 배포 설정
11. ✅ IAM Instance Profile 설정 (EC2 → ECR 접근용)
