# StayBook API별 서비스 호출 및 협력 구조 분석 보고서

StayBook 백엔드의 전체 15개 Controller와 각 API 핸들러 메서드, 내부 서비스 간 호출 및 이벤트 리스너(트랜잭션 이벤트 포함)를 전수 분석하여 **2개 이상의 서비스가 협력·호출되는 API 엔드포인트(총 33개 + 이벤트 리스너 연계 1개)**를 정리한 문서입니다.

---

## 1. 한눈에 보는 API별 다중 서비스 호출 요약

| No | HTTP Method & 경로 | Controller 메서드 | 호출되는 서비스 체인 (호출 순서순) | 방식 |
| :---: | :--- | :--- | :--- | :---: |
| **1** | `POST /api/auth/signup` | `AuthController.signup` | **AuthService** → **EmailVerificationService** → **UserService** → **TermsService** | 동기 |
| **2** | `GET /api/auth/check-email` | `AuthController.checkEmail` | **AuthService** → **UserService** | 동기 |
| **3** | `POST /api/auth/login` | `AuthController.login` | **AuthService** → **UserService** → **TokenService** | 동기 |
| **4** | `POST /api/auth/refresh` | `AuthController.refresh` | **TokenService** → **UserService** | 동기 |
| **5** | `POST /api/auth/password/forgot` | `AuthController.forgotPassword` | **PasswordResetService** → **UserService** | 동기 |
| **6** | `POST /api/auth/signup/complete` | `AuthController.completeSignup` | **UserService** → **TermsService** | 동기 |
| **7** | `POST /api/auth/social/{provider}` | `SocialAuthController.login` | **SocialLoginService** → **UserService** → **TokenService** | 동기 |
| **8** | `POST /api/host-businesses` | `HostBusinessController.register` | **HostService** → **UserService** | 동기 |
| **9** | `POST /api/host/accommodations` | `HostAccommodationController.create` | **HostAccommodationService** → **HostService** → **AccommodationQueryService** | 동기 |
| **10** | `GET /api/host/accommodations` | `HostAccommodationController.getMine` | **HostAccommodationService** → **HostService** → **AccommodationQueryService** | 동기 |
| **11** | `GET /api/host/accommodations/{id}` | `HostAccommodationController.getMyDetail` | **HostAccommodationService** → **HostService** → **AccommodationQueryService** | 동기 |
| **12** | `PATCH /api/host/accommodations/{id}` | `HostAccommodationController.update` | **HostAccommodationService** → **HostService** → **AccommodationQueryService** | 동기 |
| **13** | `PATCH /api/host/accommodations/{id}/listing-status` | `HostAccommodationController.changeListingStatus` | **HostAccommodationService** → **HostService** → **AccommodationQueryService** | 동기 |
| **14** | `POST /api/host/accommodations/{id}/rooms` | `HostAccommodationController.addRoom` | **HostAccommodationService** → **HostService** → **AccommodationQueryService** | 동기 |
| **15** | `PATCH /api/host/accommodations/{id}/rooms/{roomId}` | `HostAccommodationController.updateRoom` | **HostAccommodationService** → **HostService** → **AccommodationQueryService** | 동기 |
| **16** | `PUT /api/host/accommodations/{id}/refund-policy` | `HostAccommodationController.putRefundPolicy` | **HostAccommodationService** → **HostService** → **AccommodationQueryService** | 동기 |
| **17** | `GET /api/accommodations/{id}/availability` | `InventoryController.getAvailability` | **InventoryService** → **AccommodationQueryService** | 동기 |
| **18** | `GET /api/host/rooms/{roomId}/inventories` | `InventoryController.getInventories` | **InventoryService** → **AccommodationQueryService** → **HostService** | 동기 |
| **19** | `PUT /api/host/rooms/{roomId}/inventories` | `InventoryController.setInventories` | **InventoryService** → **AccommodationQueryService** → **HostService** | 동기 |
| **20** | `POST /api/reservations` | `ReservationController.create` | **ReservationService** → **UserService** → **AccommodationQueryService** → **InventoryService** | 동기 |
| **21** | `GET /api/reservations/me` | `ReservationController.getMine` | **ReservationService** → **AccommodationQueryService** | 동기 |
| **22** | `GET /api/reservations/{id}` | `ReservationController.getDetail` | **ReservationService** → **AccommodationQueryService** | 동기 |
| **23** | `POST /api/reservations/{id}/cancel` | `ReservationController.cancel` | **ReservationService** → **InventoryService** → **AccommodationQueryService** → **PaymentService** | 동기 + 이벤트 |
| **24** | `GET /api/host/reservations` | `ReservationController.getForHost` | **ReservationService** → **AccommodationQueryService** → **HostService** | 동기 |
| **25** | `POST /api/payments/prepare` | `PaymentController.prepare` | **PaymentService** → **ReservationService** → **AccommodationQueryService** | 동기 |
| **26** | `POST /api/payments/confirm` | `PaymentController.confirm` | **PaymentService** → **ReservationService** → **AccommodationQueryService** → **InventoryService** | 동기 |
| **27** | `POST /api/payments/webhook` | `PaymentWebhookController.receive` | **PaymentService** → **ReservationService** → **InventoryService** | 동기 |
| **28** | `GET /api/accommodations/{id}/reviews` | `ReviewController.getForAccommodation` | **ReviewService** → **AccommodationQueryService** → **UserService** | 동기 |
| **29** | `GET /api/accommodations/{id}/tracks` | `ReviewController.getTracksForAccommodation` | **ReviewService** → **AccommodationQueryService** | 동기 |
| **30** | `POST /api/reviews` | `ReviewController.create` | **ReviewService** → **ReservationService** → **AccommodationQueryService** → **UserService** | 동기 |
| **31** | `PATCH /api/reviews/{id}` | `ReviewController.update` | **ReviewService** → **UserService** | 동기 |
| **32** | `POST /api/favorites/{accommodationId}` | `FavoriteController.add` | **FavoriteService** → **AccommodationQueryService** | 동기 |
| **33** | `GET /api/favorites/me` | `FavoriteController.getMine` | **FavoriteService** → **AccommodationQueryService** | 동기 |
| *34 | `DELETE /api/users/me` *(참고)* | `UserController.withdraw` | **UserService** → **UserWithdrawnListener** (`RefreshTokenStore`) | 이벤트 |

---

## 2. 도메인별 상세 호출 흐름 및 메서드 명세

### 🔐 1. 인증 도메인 (`auth`)

#### 1. 일반 회원가입: `POST /api/auth/signup`
- **1) `AuthService.signup(request)`**: 회원가입 총괄 오케스트레이션
- **2) `EmailVerificationService.assertVerified(email)`**: 최근 이메일 인증 완료 여부 사전 검증
- **3) `UserService.register(command)`**: 만 14세 이상 검증, 이메일 중복 확인, `User` 및 개인정보 생성
- **4) `TermsService.agree(userId, termIds)`**: 필수 약관 동의 검증 및 버전별 동의 이력 저장 (DIP)

#### 2. 이메일 중복 체크: `GET /api/auth/check-email`
- **1) `AuthService.isEmailAvailable(email)`**: 이메일 가입 가능 여부 확인
- **2) `UserService.existsByEmail(email)`**: 회원 테이블에서 암호화 해시로 이메일 중복 조회

#### 3. 일반 로그인: `POST /api/auth/login`
- **1) `AuthService.login(request, client)`**: 계정 잠금 확인, 비밀번호 검증, 토큰 발급 위임
- **2) `UserService.findAccountByEmail(email)`**: 로그인 이메일로 활성 계정 정보(ID, Role) 조회
- **3) `TokenService.issue(account, client)`**: JWT Access/Refresh 토큰 생성 및 Redis에 Refresh Token 저장

#### 4. 토큰 재발급 (RTR): `POST /api/auth/refresh`
- **1) `TokenService.refresh(refreshToken, client)`**: 기존 리프레시 토큰 폐기 및 신규 토큰 발급
- **2) `UserService.findAccount(userId)`**: 회원 탈퇴 여부 및 최신 권한 상태 재확인

#### 5. 비밀번호 재설정 요청: `POST /api/auth/password/forgot`
- **1) `PasswordResetService.requestReset(email)`**: 일회용 재설정 토큰 생성 및 이메일 발송
- **2) `UserService.findAccountByEmail(email)`**: 재설정 대상 회원 계정 실존 여부 확인

#### 6. 소셜 가입 추가정보 입력: `POST /api/auth/signup/complete`
- **1) `UserService.completeSignup(userId, birthDate, termIds)`**: 생년월일 성인 검증 및 회원 활성화
- **2) `TermsService.agree(userId, termIds)`**: 필수 약관 동의 검증 및 동의 이력 저장

#### 7. 소셜 로그인/가입: `POST /api/auth/social/{provider}`
- **1) `SocialLoginService.login(provider, code, state, client)`**: 소셜 OAuth 프로필 조회
- **2) `UserService.registerSocial(name, email)` / `findAccount(userId)`**: 신규 소셜 회원 등록 또는 기존 계정 조회
- **3) `TokenService.issue(account, client)`**: Access/Refresh 토큰 생성 및 Redis 저장

---

### 🏢 2. 호스트 사업자 도메인 (`host`)

#### 8. 호스트 사업자 등록: `POST /api/host-businesses`
- **1) `HostService.register(userId, request)`**: 사업자번호 유효성 검증 및 `HostBusiness` 등록
- **2) `UserService.changeRole(userId, HOST)`**: 회원 권한을 `GUEST`에서 `HOST`로 승급 후 새 토큰 발급

---

### 🏠 3. 호스트 숙소 관리 도메인 (`accommodation`)
*(호스트 숙소 관리의 8개 API는 모두 **HostAccommodationService → HostService(소유권) → AccommodationQueryService(DTO 조립)**의 3단계 협력 구조를 갖습니다.)*

- **9. 숙소 등록**: `POST /api/host/accommodations`
- **10. 내 숙소 목록**: `GET /api/host/accommodations`
- **11. 내 숙소 상세**: `GET /api/host/accommodations/{id}`
- **12. 숙소 정보 수정**: `PATCH /api/host/accommodations/{id}`
- **13. 공개 상태 변경**: `PATCH /api/host/accommodations/{id}/listing-status`
- **14. 객실 추가**: `POST /api/host/accommodations/{id}/rooms`
- **15. 객실 수정/마감**: `PATCH /api/host/accommodations/{id}/rooms/{roomId}`
- **16. 환불정책 갱신**: `PUT /api/host/accommodations/{id}/refund-policy`
  - **호출 체인**:
    1. `HostAccommodationService`: 숙소/객실 CUD 로직 실행
    2. `HostService.getBusinessOf(userId)`: 해당 호스트의 사업자 ID 확인 및 소유권 검증
    3. `AccommodationQueryService.toDetail` / `getHostSummaries` / `toRoomResponse` / `toPolicyResponse`: 응답 DTO 조립 재사용

---

### 📦 4. 재고 도메인 (`inventory`)

#### 17. 숙소 잔여 객실/요금 조회: `GET /api/accommodations/{id}/availability`
- **1) `InventoryService.getAvailability(accommodationId, checkIn, checkOut)`**: 일자별 가용성 및 총 숙박료 계산
- **2) `AccommodationQueryService.getSellableRooms(accommodationId)`**: 해당 숙소의 판매 가능한 활성 객실 목록 조회

#### 18. [호스트] 일별 재고 현황 조회: `GET /api/host/rooms/{roomId}/inventories`
- **1) `InventoryService.getInventories(userId, roomId, start, end)`**: 기간별 재고 목록 조회
- **2) `AccommodationQueryService.assertRoomOwner(roomId, userId)`**: 호스트의 객실 소유권 검증
- **3) `HostService.getBusinessOf(userId)`**: 호스트 사업자 ID 검증

#### 19. [호스트] 일별 수량/가격 설정: `PUT /api/host/rooms/{roomId}/inventories`
- **1) `InventoryService.setInventories(userId, roomId, request)`**: 일별 재고 수량/가격 갱신 및 마감 설정
- **2) `AccommodationQueryService.assertRoomOwner` & `getRoomInfo`**: 객실 소유권 검증 및 기본 가격 폴백 조회
- **3) `HostService.getBusinessOf(userId)`**: 호스트 사업자 ID 검증

---

### 📅 5. 예약 도메인 (`reservation`)

#### 20. 예약 생성 (재고 선점): `POST /api/reservations`
- **1) `ReservationService.create(userId, request)`**: PENDING 예약 생성 및 요금 계산
- **2) `UserService.isAdult(userId)` & `getMe(userId)`**: 만 19세 성인 검증 및 예약자 정보 채움
- **3) `AccommodationQueryService.getRoomInfo` & `getActiveRefundPolicy`**: 객실 정원 확인 및 환불 규정 스냅샷 조회
- **4) `InventoryService.hold(reservationId, roomId, checkIn, checkOut, expiresAt)`**: 기간 내 일별 재고 조건부 원자적 선점

#### 21. 내 예약 목록 조회: `GET /api/reservations/me`
- **1) `ReservationService.getMine(userId, page, size)`**: 본인 예약 페이징 조회
- **2) `AccommodationQueryService.getRoomInfo(roomId)`**: 응답 DTO에 숙소명/객실명 정보 바인딩

#### 22. 예약 상세 조회: `GET /api/reservations/{id}`
- **1) `ReservationService.getDetail(userId, reservationId)`**: 단건 예약 상세 조회
- **2) `AccommodationQueryService.getRoomInfo(roomId)`**: 숙소 및 객실 메타데이터 바인딩

#### 23. 예약 취소: `POST /api/reservations/{id}/cancel` *(동기 + 이벤트)*
- **1) `ReservationService.cancel(userId, reservationId)`**: 취소 상태 전이 및 환불율 계산
- **2) `InventoryService.release(reservationId)`**: 선점 또는 예약 확정되었던 재고 수량 반환
- **3) `AccommodationQueryService.getRoomInfo(roomId)`**: 취소 완료 응답 DTO 조립
- **4) `PaymentService.refundForCancellation(event)`** `[이벤트: AFTER_COMMIT]`:
  - `ReservationCancelledListener`가 트랜잭션 커밋 직후 새 트랜잭션(`REQUIRES_NEW`)을 열어 PG 결제 취소 및 `Refund` 레코드 생성

#### 24. [호스트] 예약 접수 내역 조회: `GET /api/host/reservations`
- **1) `ReservationService.getForHost(hostUserId, page, size)`**: 호스트 숙소 예약 목록 조회
- **2) `AccommodationQueryService.getRoomIdsOfHost(userId)` & `getRoomInfo`**: 호스트 소유 객실 ID 목록 추출
- **3) `HostService.getBusinessOf(userId)`**: 호스트 사업자 ID 검증

---

### 💳 6. 결제 도메인 (`payment`)

#### 25. 결제 준비: `POST /api/payments/prepare`
- **1) `PaymentService.prepare(userId, request)`**: 주문번호(`order_id`) 채번 및 READY 결제 레코드 생성
- **2) `ReservationService.getPaymentInfo(reservationId)`**: 예약 소유권, PENDING 상태, 결제 총액 검증
- **3) `AccommodationQueryService.getRoomInfo(roomId)`**: PG 결제창에 표기할 상품명("숙소명 객실명") 조립

#### 26. 결제 승인 및 확정: `POST /api/payments/confirm`
- **1) `PaymentService.confirm(userId, request)`**: 위변조 검증, PG 승인 API 호출, PAID 상태 갱신
- **2) `ReservationService.getPaymentInfo` & `confirm(reservationId)`**: 금액 재확증 및 예약을 CONFIRMED로 전이
- **3) `AccommodationQueryService.getRoomInfo(roomId)`**: 객실 정보 조회
- **4) `InventoryService.confirm(reservationId)`**: 선점 재고(held)를 확정 재고(booked)로 전환

#### 27. PG 웹훅 수신: `POST /api/payments/webhook`
- **1) `PaymentService.handleWebhook(rawBody, headers)`**: 웹훅 서명 검증 및 멱등 결제 완료 처리
- **2) `ReservationService.confirm(reservationId)`**: 예약 확정 상태 전이
- **3) `InventoryService.confirm(reservationId)`**: 재고 확정 전환

---

### ⭐ 7. 리뷰 도메인 (`review`)

#### 28. 숙소 리뷰 목록 조회: `GET /api/accommodations/{id}/reviews`
- **1) `ReviewService.getForAccommodation(...)`**: 노출 상태 리뷰 목록 조회
- **2) `AccommodationQueryService.existsListed(accommodationId)`**: 공개 숙소 여부 확인
- **3) `UserService.findName(userId)`**: 작성자 이름 마스킹(`홍**`) 표기를 위한 회원명 조회

#### 29. 숙소 추천 트랙 목록: `GET /api/accommodations/{id}/tracks`
- **1) `ReviewService.getTracksForAccommodation(accommodationId)`**: 숙소 리뷰의 Spotify 트랙 중복 제거 조회
- **2) `AccommodationQueryService.existsListed(accommodationId)`**: 공개 숙소 여부 확인

#### 30. 리뷰 작성: `POST /api/reviews`
- **1) `ReviewService.create(userId, request)`**: 리뷰 작성 및 별점/음악 저장
- **2) `ReservationService.getStayInfo(reservationId)`**: 본인의 투숙 완료(`COMPLETED`) 예약인지 검증
- **3) `AccommodationQueryService.getRoomInfo(roomId)`**: 숙소 ID 매핑 검증
- **4) `UserService.findName(userId)`**: 응답 작성자 이름 마스킹 조회

#### 31. 리뷰 수정: `PATCH /api/reviews/{id}`
- **1) `ReviewService.update(userId, reviewId, request)`**: 본인 리뷰 별점/트랙 수정
- **2) `UserService.findName(userId)`**: 응답 DTO 작성자명 마스킹 조회

---

### ❤️ 8. 찜 도메인 (`favorite`)

#### 32. 숙소 찜하기: `POST /api/favorites/{accommodationId}`
- **1) `FavoriteService.add(userId, accommodationId)`**: 찜 등록 (멱등)
- **2) `AccommodationQueryService.existsListed(accommodationId)`**: 공개 숙소 여부 확인

#### 33. 내 찜 목록 조회: `GET /api/favorites/me`
- **1) `FavoriteService.getMine(userId, page, size)`**: 찜 목록 페이징 조회
- **2) `AccommodationQueryService.getListedSummaries(ids)`**: 찜한 숙소들의 썸네일/최저가 등 요약 정보 일괄 바인딩

---

### 👤 9. 회원 탈퇴 *(참고: 이벤트 기반 컴포넌트 연계)*

#### *34. 회원 탈퇴: `DELETE /api/users/me`
- **1) `UserService.withdraw(userId)`**: 회원 비활성화(Soft Delete) 및 `UserWithdrawnEvent` 발행
- **2) `UserWithdrawnListener.onUserWithdrawn(event)`**: `@EventListener`로 수신하여 Redis의 모든 Refresh Token 즉시 삭제

---

## 3. 단일 서비스만 호출하여 제외된 엔드포인트 목록 (18개 검증)

아래 API들은 컨트롤러에서 단 1개의 서비스만 호출하며, 해당 서비스 내부에서도 타 도메인 서비스를 일절 호출하지 않고 자체 Repository/Client만 처리합니다:

1. `POST /api/auth/logout` (`TokenService`)
2. `POST /api/auth/email/verification-code` (`EmailVerificationService`)
3. `POST /api/auth/email/verify` (`EmailVerificationService`)
4. `POST /api/auth/password/reset` (`PasswordResetService`)
5. `GET /api/auth/social/{provider}/authorize` (`SocialLoginService`)
6. `POST /api/auth/social/{provider}/link` (`SocialLoginService`)
7. `GET /api/users/me` (`UserService`)
8. `PUT /api/users/me` (`UserService`)
9. `GET /api/host-businesses/me` (`HostService`)
10. `GET /api/accommodations/regions` (`AccommodationQueryService`)
11. `GET /api/accommodations/amenities` (`AccommodationQueryService`)
12. `GET /api/accommodations/categories` (`AccommodationQueryService`)
13. `GET /api/accommodations` (검색) (`AccommodationQueryService`)
14. `GET /api/accommodations/{id}` (상세) (`AccommodationQueryService`)
15. `DELETE /api/reviews/{id}` (`ReviewService`)
16. `GET /api/spotify/tracks/search` (`SpotifyService`)
17. `DELETE /api/favorites/{accommodationId}` (`FavoriteService`)
18. `GET /api/terms` (`TermsService`)
19. `POST /api/images` (`ImageUploadService`)
