# StayBook 코드 읽는 법 (아키텍처 규칙)

이 문서는 "이 로직이 왜 여기에 있는가"를 설명합니다. 요구사항 문서가 **무엇을** 만드는지 적은 것이라면, 이 문서는 코드가 **어떤 규칙으로** 나뉘어 있는지 적은 것입니다.

읽는 순서: 1~3장을 읽고 → 6장의 "예약 생성"을 코드와 나란히 따라가 보고 → 나머지를 필요할 때 찾아보세요.

---

## 1. 큰 그림

### 패키지

`com.staybook` 아래에 `common`과 기능(feature) 10개가 있고, 기능마다 같은 모양의 하위 패키지를 가집니다.

```
com.staybook
├── common          어느 기능에도 속하지 않는 것 (보안, 암호화, 예외, 응답 형식, Redis, 이미지 저장)
├── user            ┐
├── auth            │
├── terms           │
├── host            │   기능마다 아래 6개 패키지
├── accommodation   │     controller   HTTP 요청을 받는 곳
├── inventory       │     service      업무 규칙과 흐름
├── reservation     │     domain       엔티티 (테이블 한 행)
├── payment         │     repository   DB 접근
├── review          │     dto          요청·응답 모양
└── favorite        ┘     exception    그 기능의 에러 코드
```

기능에 따라 외부 연동용 패키지가 하나 더 있습니다: `auth/oauth`(카카오·네이버), `payment/pg`(토스페이먼츠), `review/spotify`.

### 요청 한 건이 지나가는 길

```
HTTP 요청
  → JwtAuthenticationFilter   토큰을 읽어 "누구인지"를 만든다 (common/security)
  → SecurityConfig            경로별 권한 검사 (/api/host/** 는 HOST만)
  → Controller                요청 형식 검증(@Valid), 서비스 호출, ApiResponse로 감싸기
  → Service                   업무 규칙 검사, 트랜잭션, 다른 기능의 서비스 호출
  → Repository / Domain       DB 읽기·쓰기
  ← 예외가 나면 GlobalExceptionHandler가 에러 응답으로 바꾼다
```

---

## 2. 레이어별로 하는 일과 하지 않는 일

| 레이어 | 하는 일 | 하지 않는 일 |
|---|---|---|
| **controller** | 경로 매핑, `@Valid`로 형식 검증, 로그인한 회원(`AuthUser`) 꺼내기, 서비스 메서드 **한 번** 호출, `ApiResponse.ok(...)`로 감싸기 | 업무 규칙 판단, 리포지터리 호출, 트랜잭션 |
| **service** | 업무 규칙 검사, 여러 테이블에 걸친 흐름, 트랜잭션 경계, 다른 기능 호출, 엔티티 → DTO 변환 | HTTP에 관한 것 (상태 코드는 에러 코드 enum에 있음) |
| **domain** | 테이블 한 행의 값을 담고, **자기 필드를 바꾸는 메서드**를 제공 | DB 조회, 다른 엔티티·서비스 호출, 예외 던지기 |
| **repository** | DB 읽기·쓰기. 잠금과 조건부 UPDATE처럼 **DB가 보장해야 하는 것** | 업무 규칙 판단 |
| **dto** | 요청·응답의 모양과 형식 검증 애너테이션. 다른 기능에 넘겨주는 데이터 묶음 | 로직 |
| **exception** | 그 기능의 에러 코드 enum (HTTP 상태, 코드, 메시지) | — |

컨트롤러는 전부 같은 모양입니다. 하나만 읽으면 나머지는 경로만 보면 됩니다.

```java
@PostMapping("/api/reservations/{id}/cancel")
public ApiResponse<CancelResponse> cancel(@AuthenticationPrincipal AuthUser authUser, @PathVariable Long id) {
    return ApiResponse.ok(reservationService.cancel(authUser.userId(), id));
}
```

---

## 3. 이 로직은 어디에 두는가

질문하신 "어떤 건 도메인 메서드, 어떤 건 리포지터리 메서드"의 기준입니다. 위에서부터 차례로 물어보면 됩니다.

| 질문 | 두는 곳 | 이 코드의 예 |
|---|---|---|
| ① 요청 값의 **형식**이 맞는가? (비어 있음, 길이, 범위) | **dto**의 검증 애너테이션 | `@NotNull Long categoryId`, `@Min(1) Integer capacity` |
| ② **한 행의 필드를 바꾸는 것**인가? | **domain 메서드** | `reservation.confirm()`, `payment.markPaid(...)`, `inventory.change(...)` |
| ③ **동시에 두 요청이 와도** 틀리면 안 되는 숫자 증감인가? | **repository의 조건부 UPDATE** | `tryHold`, `moveHeldToBooked`, `decreaseHeld` |
| ④ 현재 상태를 **확인한 뒤** 바꿔야 하는가? | **repository의 `...ForUpdate`로 잠그고** → service에서 확인 → domain 메서드로 변경 | `findByIdForUpdate` → `isStatus(PENDING)` → `confirm()` |
| ⑤ "없으면 만들고 있으면 그대로"인가? | **repository의 `INSERT IGNORE`** | `insertIfAbsent`(재고), 찜 추가 |
| ⑥ 여러 테이블이나 **다른 기능**이 필요한가? | **service** | 예약 생성 = 회원 확인 + 객실 조회 + 재고 선점 + 요금 저장 |
| ⑦ 조회 조건이 복잡한가? | **repository의 `@Query`** | 숙소 검색(지역·카테고리·인원) |

### ②와 ③의 차이가 핵심입니다

둘 다 "값을 바꾼다"지만 방식이 다릅니다.

**② 도메인 메서드**: 엔티티를 읽어 와서 자바에서 값을 바꾸면, 트랜잭션이 끝날 때 JPA가 UPDATE를 보냅니다. 리포지터리의 `save()`를 다시 부르지 않습니다.

```java
// Reservation.java
public void cancel(LocalDateTime now) {
    this.status = Status.CANCELLED;
    this.cancelledAt = now;
}
```

**③ 조건부 UPDATE**: 읽지 않고 DB에 "조건이 맞으면 바꿔라"를 한 번에 보냅니다. 바뀐 행 수(0 또는 1)로 성공 여부를 압니다.

```java
// RoomInventoryRepository.java
@Query("""
        update RoomInventory i set i.heldCount = i.heldCount + 1
        where i.id = :id and i.closed = false and i.bookedCount + i.heldCount < i.totalCount
        """)
int tryHold(@Param("id") Long id);   // 0이면 매진
```

재고를 ② 방식으로 하면 안 되는 이유: 두 사람이 동시에 "남은 수량 1"을 읽고 각자 +1을 하면 둘 다 성공해 버립니다. ③은 확인과 증가가 DB 안에서 한 번에 일어나므로 한 명만 성공합니다. 그래서 `RoomInventory`의 `bookedCount`, `heldCount`에는 도메인 메서드가 없고(`updatable = false`), 호스트가 바꾸는 `totalCount`·`price`·`closed`만 `change()`로 바꿉니다.

### 도메인 메서드는 얇습니다

이 코드의 도메인 메서드는 **값만 바꾸고 검사는 하지 않습니다.** `reservation.confirm()`은 현재 상태가 PENDING인지 확인하지 않습니다. 확인은 서비스가 합니다.

```java
// ReservationService.confirm
Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)...   // ④ 잠그고 읽기
if (!reservation.isStatus(Status.PENDING)) { return false; }                          //    서비스가 확인
reservation.confirm();                                                                // ② 값 변경
inventoryService.confirm(reservationId);                                              // ⑥ 다른 기능
recordHistory(...);                                                                   //    이력 저장
```

그래서 "어떤 상태에서 어떤 상태로 갈 수 있는가"를 알고 싶으면 엔티티가 아니라 **서비스 클래스 맨 위 주석과 메서드**를 보세요. (`ReservationService` 상단에 상태 전이표가 있습니다.)

### 엔티티는 생성자 대신 정적 메서드로 만듭니다

`new`로 직접 만들 수 없게 막아 두고(`protected` 생성자), 이름 있는 메서드로 만듭니다. 이름이 "어떤 상태로 시작하는지"를 말해 줍니다.

| 이름 | 뜻 | 예 |
|---|---|---|
| `create(...)` | 일반 생성 | `Accommodation.create` (DRAFT로 시작) |
| `of(...)` | 값 묶음에 가까운 단순 생성 | `ReservationPriceItem.of`, `PaymentLog.of` |
| 상태 이름 | 그 상태로 생성 | `Reservation.pending`, `Payment.ready`, `InventoryHold.held`, `Refund.requested` |

---

## 4. 기능끼리 지키는 규칙

### 의존 방향

화살표 방향으로만 import할 수 있습니다. 거꾸로는 안 됩니다.

```
review ──▶ reservation ──▶ inventory ──▶ accommodation ──▶ host ──▶ user
payment ─▶ reservation                       ▲                      ▲
favorite ────────────────────────────────────┘        auth, terms ──┘
common 은 어떤 기능도 import하지 않는다
```

예: `reservation`은 `inventory`를 부를 수 있지만, `inventory`는 `reservation`을 모릅니다. 그래서 재고 선점 메서드는 예약 엔티티가 아니라 **예약 ID(Long)** 만 받습니다.

### 다른 기능을 쓸 때의 세 가지 약속

1. **서비스만 부른다.** 다른 기능의 리포지터리나 엔티티를 직접 쓰지 않습니다.
2. **ID로 참조한다.** `Reservation`은 `Room` 객체가 아니라 `Long roomId`를 가집니다. 엔티티 사이에 `@ManyToOne` 같은 연관 관계가 없습니다.
3. **DTO로 받는다.** 다른 기능에 넘겨주는 데이터는 엔티티가 아니라 그 용도의 DTO입니다.

| 주는 쪽 | DTO | 받는 쪽이 쓰는 곳 |
|---|---|---|
| accommodation | `RoomInfo` | 예약 생성 시 객실 인원·요금·판매 여부 확인 |
| inventory | `NightPrice` | 예약의 날짜별 요금 계산 |
| reservation | `PaymentInfo` | 결제 금액 검증 |
| reservation | `StayInfo` | 리뷰 작성 자격 확인 |

서비스 안의 `// ---------- 다른 feature용 ----------`, `// ---------- payment용 ----------` 같은 구분선 아래가 다른 기능에 열어 둔 메서드입니다.

이 규칙들은 사람이 지키는 것이 아니라 [DependencyRuleTest](../src/test/java/com/staybook/DependencyRuleTest.java)가 검사합니다. 어기면 테스트가 실패합니다.

### 거꾸로 알려야 할 때

화살표 반대 방향으로 일을 시켜야 하는 경우가 세 군데 있고, 각각 방법이 다릅니다.

| 상황 | 방법 | 위치 |
|---|---|---|
| 가입(user)할 때 약관 동의(terms)를 기록해야 함 | **인터페이스를 아래쪽에 둔다.** user에 `TermsAgreementHandler`를 두고 terms가 구현 | `user/service/TermsAgreementHandler`, `terms/service/TermsService` |
| 탈퇴(user)하면 리프레시 토큰(auth)을 지워야 함 | **이벤트.** user가 `UserWithdrawnEvent`를 발행, auth가 듣는다 | `auth/service/UserWithdrawnListener` |
| 예약 취소(reservation)하면 환불(payment)해야 함 | **커밋 이후 이벤트.** `ReservationCancelledEvent` | `payment/service/ReservationCancelledListener` |

---

## 5. 트랜잭션 규칙

기본은 서비스 메서드에 `@Transactional`입니다. 메서드가 예외로 끝나면 그 안의 DB 변경이 모두 취소됩니다. 조회 전용은 `@Transactional(readOnly = true)`.

기본에서 벗어난 곳은 아래 네 가지뿐이고, 모두 이유가 있습니다.

| 벗어난 방식 | 어디 | 왜 |
|---|---|---|
| `noRollbackFor = BaseException.class` | 로그인, 이메일 인증코드 확인 | **실패 횟수를 저장한 채로 실패 응답**을 해야 함. 롤백되면 "5회 실패 시 잠금"이 동작하지 않음 |
| `TransactionTemplate` + `Outcome` | `PaymentService.confirm` | PG 승인이 거절돼도 **결제를 FAILED로 저장한 뒤** 에러를 응답해야 함. 트랜잭션 안에서는 결과만 만들고, 커밋 후에 예외를 던짐 |
| `TransactionTemplate`을 반복문 안에서 | 스케줄러(`expirePending`, `completeStays`) | 예약 **한 건씩 따로 커밋**. 한 건이 실패해도 나머지는 처리됨 |
| `AFTER_COMMIT` 이벤트 + 새 트랜잭션 | 예약 취소 → 환불 | 예약 취소가 **확정된 뒤에** PG에 환불 요청. 환불이 실패해도 예약 취소는 유지되고 환불은 FAILED로 남음 |

### 잠금을 거는 곳

"확인하고 바꾸는" 사이에 다른 요청이 끼어들면 안 되는 곳은 행을 잠급니다(`SELECT ... FOR UPDATE`). 메서드 이름이 `...ForUpdate`로 끝납니다.

| 잠그는 행 | 메서드 | 서로 막는 것 |
|---|---|---|
| 예약 | `ReservationRepository.findByIdForUpdate` | 결제 확정 / 사용자 취소 / 만료 스케줄러 |
| 결제 | `PaymentRepository.findByOrderIdForUpdate` | 승인 요청 중복 / 승인과 웹훅 동시 도착 |

### 두 번 불려도 결과가 같게

결제 승인, 웹훅, 재고 확정·해제는 같은 요청이 두 번 올 수 있다고 보고 만들었습니다.

- 결제가 이미 PAID면 PG를 다시 부르지 않고 같은 결과를 돌려줍니다.
- 웹훅은 이벤트 ID(`provider_event_id`)가 이미 기록돼 있으면 무시합니다.
- `InventoryService.confirm`/`release`는 선점 상태를 조건부로 바꾸고(`transition`), **바뀐 경우에만** 수량을 조정합니다.

---

## 6. 흐름 따라 읽기

파일을 열어 놓고 번호 순서대로 따라가 보세요. 괄호 안 번호는 3장 표의 질문 번호입니다.

### 예약 생성 — `POST /api/reservations`

[ReservationController](../src/main/java/com/staybook/reservation/controller/ReservationController.java) → [ReservationService.create](../src/main/java/com/staybook/reservation/service/ReservationService.java)

| 순서 | 하는 일 | 어디서 |
|---|---|---|
| 1 | 요청 형식 검증 (①) | `CreateRequest`의 애너테이션 |
| 2 | 날짜 검사: 체크아웃 > 체크인, 과거 아님 | service |
| 3 | 성인인지 (⑥) | `userService.isAdult` |
| 4 | 객실 정보, 판매 중인지, 인원 초과 아닌지 (⑥) | `accommodationQueryService.getRoomInfo` → `RoomInfo` |
| 5 | 현재 환불정책 가져오기 | `getActiveRefundPolicy` |
| 6 | 예약을 PENDING으로 저장. 환불정책을 JSON으로 함께 저장 | `Reservation.pending(...)` + `save` |
| 7 | 숙박일마다 재고 1개씩 선점 (③) | [InventoryService.hold](../src/main/java/com/staybook/inventory/service/InventoryService.java) → `tryHold` |
| 8 | 요금 항목 저장 (객실 요금, 추가 인원 요금) | `ReservationPriceItem.of` |
| 9 | 합계를 예약에 반영 (②) | `reservation.changeTotalPrice` |
| 10 | 상태 이력 저장 | `recordHistory` |

전체가 한 트랜잭션입니다. 7번에서 하루라도 매진이면 예외가 나고 6번의 예약 저장까지 모두 취소됩니다.

### 결제 승인 — `POST /api/payments/confirm`

[PaymentService.confirm](../src/main/java/com/staybook/payment/service/PaymentService.java) → `doConfirm` → `completePaid`

| 순서 | 하는 일 | 어디서 |
|---|---|---|
| 1 | 결제 행을 잠그고 읽기 (④) | `findByOrderIdForUpdate` |
| 2 | 본인 예약인지, 이미 PAID면 그대로 반환 | service |
| 3 | 요청 금액 = 예약 금액인지 검증 | `reservationService.getPaymentInfo` → `PaymentInfo` |
| 4 | PG에 승인 요청, 로그 저장 | `pgClient.approve` |
| 5 | 실패면 FAILED로 표시 (②) 후 커밋하고 에러 응답 | `payment.markFailed`, `Outcome.failed` |
| 6 | 성공이면 PAID로 표시 (②) | `payment.markPaid` |
| 7 | 예약 확정: 예약 잠금 → 상태 확인 → CONFIRMED (④) | `ReservationService.confirm` |
| 8 | 재고 확정: 선점 → 예약 완료로 이동 (③) | `InventoryService.confirm` → `moveHeldToBooked` |

7번에서 예약이 이미 만료로 취소돼 있으면 `false`가 돌아오고, 결제는 그 자리에서 전액 환불됩니다.

### 예약 취소와 환불 — `POST /api/reservations/{id}/cancel`

[ReservationService.cancel](../src/main/java/com/staybook/reservation/service/ReservationService.java) → (커밋) → [ReservationCancelledListener](../src/main/java/com/staybook/payment/service/ReservationCancelledListener.java) → `PaymentService.refundForCancellation`

| 순서 | 하는 일 | 어디서 |
|---|---|---|
| 1 | 예약 행을 잠그고 읽기, 본인 것인지 (④) | `findByIdForUpdate` |
| 2 | PENDING이면 취소 + 재고 해제로 끝 | `transitionToCancelled` |
| 3 | CONFIRMED면 남은 일수로 환불 비율 계산 | `refundRate` (예약에 저장해 둔 정책 JSON 사용) |
| 4 | 취소 (②) + 재고 해제 (③) + 이력 | `reservation.cancel`, `inventoryService.release` |
| 5 | 취소 이벤트 발행 | `ReservationCancelledEvent` |
| — | **여기서 예약 쪽 트랜잭션이 커밋됨** | |
| 6 | 결제 행 잠금, 환불액 계산 | `refundForCancellation` |
| 7 | 환불 기록 저장 → PG 취소 요청 → 성공/실패 표시 | `Refund.requested`, `pgClient.cancel` |

3번에서 현재 정책이 아니라 **예약 당시의 정책**을 쓰는 것이 포인트입니다. 호스트가 나중에 환불정책을 바꿔도 기존 예약에는 영향이 없습니다.

---

## 7. 이름으로 알 수 있는 것

| 이름 | 뜻 |
|---|---|
| `XxxService` | 업무 흐름 |
| `XxxQueryService` | 조회 전용 서비스 (`AccommodationQueryService`) |
| `HostXxxService`, `HostXxxController` | 호스트 전용. 메서드마다 본인 소유인지 먼저 검사 |
| `XxxScheduler` | 정해진 시각에 서비스 메서드를 부르기만 함 |
| `XxxListener` | 이벤트를 받아 서비스 메서드를 부르기만 함 |
| `XxxEvent` | 다른 기능에 알리는 사건 |
| `XxxProperties` | `application.yml`의 설정 값 묶음 |
| `XxxClient`, `XxxApi` | 외부 서비스 호출 (PG, OAuth, Spotify). 인터페이스 + 실제 구현 + 테스트용 구현 |
| `XxxDtos` | 그 기능의 요청·응답 record 모음 |
| `XxxErrorCode` | 그 기능의 에러 코드 enum |
| `find...ForUpdate` | 행을 잠그고 읽음 |
| `try...`, `decrease...`, `transition` | 조건부 UPDATE. 반환값은 바뀐 행 수 |
| `...IfAbsent` | 없을 때만 INSERT |
| `assert...` | 조건이 틀리면 예외 |
| `toResponse`, `toDetail`, `toSummaries` | 엔티티 → 응답 DTO 변환 |

### 에러가 나가는 길

서비스에서 `throw new BaseException(ReservationErrorCode.NOT_CANCELLABLE)`을 던지면, `GlobalExceptionHandler`가 enum에 적힌 HTTP 상태·코드·메시지로 응답을 만듭니다. 에러 코드는 `기능이름-번호` 형식입니다(`ACCOMMODATION-009`). 형식 검증(`@Valid`) 실패는 `COMMON-001`입니다.

---

## 8. 추천하는 읽는 순서

1. [FullScenarioTest](../src/test/java/com/staybook/FullScenarioTest.java) — 서비스 전체가 무엇을 하는지
2. `reservation` 패키지 전체 — 가장 전형적인 기능. 6장을 옆에 두고
3. `inventory`의 [RoomInventoryRepository](../src/main/java/com/staybook/inventory/repository/RoomInventoryRepository.java)와 `InventoryService` — 조건부 UPDATE
4. `payment`의 `PaymentService` — 트랜잭션을 직접 다루는 경우
5. `common/security` — 요청이 컨트롤러에 닿기 전에 일어나는 일
6. `auth` — 분량이 가장 많지만 구조는 같습니다
