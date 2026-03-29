# Loopang Gateway

Spring Cloud Gateway 기반 API Gateway 서버. MSA 아키텍처의 단일 진입점 역할을 합니다.

## 기술 스택

- Java 21, Spring Boot 3.5.13, Spring Cloud 2025.0.1
- Spring Cloud Gateway (Reactive, WebFlux 기반)
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

## 인프라 구성 요약

| 리소스 | 설명 |
|---|---|
| ECR | Docker 이미지 저장소 |
| EC2 | t3.micro, Amazon Linux 2023 |
| ALB | HTTP→HTTPS 리디렉션, HTTPS→TG 전달 |
| Target Group | HTTP:18080, health: /actuator/health |
| Auto Scaling Group | min=2, max=5, CPU 70% Target Tracking |
| Launch Template | User Data로 Docker 자동 실행 |
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

## Blue/Green 배포 흐름 (예정)

1. Green ASG 생성 (GitHub Actions → 새 Docker 이미지)
2. Green 헬스체크 + smoke test
3. ALB 트래픽 전환 (Green 100%)
4. Blue ASG 삭제

## 인프라 구축 진행 상황

1. ✅ ECR (이미지 저장소)
2. ✅ EC2 수동 배포 (단일 실행 검증)
3. ✅ Elastic IP 연결
4. ✅ AMI 생성
5. ✅ Launch Template 생성
6. ✅ ALB + Target Group 생성
7. ✅ Auto Scaling Group 생성
8. ✅ Route53 설정 + 도메인 연결
9. ⬜ GitHub Actions CI/CD
10. ⬜ Blue/Green 배포 설정
