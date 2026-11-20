# StayBook 동시성·데드락 처리 분석 (2026-11-16)

기준: `feature/review-music-ci` 브랜치 코드 (2026-11-13)

DB 동시성과 데드락을 의식하고 작성한 코드를 기법별로 묶었다. 경로는 `src/main/java/com/staybook/` 아래 기준이다.

## 1. 조건부 UPDATE (원자적 검사 + 변경)

"읽고 판단한 뒤 쓰기"를 SQL 한 문장으로 합쳐 그 사이에 다른 요청이 끼어들 틈을 없앤다. 반영된 행 수(0 또는 1)로 성공 여부를 판정한다.

| 위치 | 조건 | 0행일 때 |
| --- | --- | --- |
| `inventory/repository/RoomInventoryRepository.java:19` `tryHold` | `closed = false and booked + held < total` | `INVENTORY-001` SOLD_OUT, 예약 전체 롤백 |
| `inventory/repository/RoomInventoryRepository.java:27` `moveHeldToBooked` | `heldCount > 0` | 무시 (이미 처리됨) |
| `inventory/repository/RoomInventoryRepository.java:34` `decreaseHeld`, `decreaseBooked` | 카운트 `> 0` | 무시. 음수 방지 |
| `inventory/repository/InventoryHoldRepository.java:24` `transition` | `status = :from` | 무시. HELD→CONFIRMED 같은 상태 전이를 한 번만 일어나게 함 |

- `InventoryService.confirm`과 `release`는 상태 전이가 1행 성공했을 때만 카운트를 바꾼다. 그래서 두 번 호출해도 결과가 같다.
- 조건부 UPDATE 자체는 오류를 던지지 않는다. 서비스가 0행을 보고 `BaseException(InventoryErrorCode.SOLD_OUT)`로 바꾸며, 이 에러는 HTTP 409로 프론트에 전달된다.
- 락을 기다리던 UPDATE는 앞선 트랜잭션이 끝난 뒤 최신 값으로 조건을 다시 평가한다. 기다렸다는 이유만으로 실패하지는 않는다.

## 2. 비관적 락 (`SELECT ... FOR UPDATE`) + 잠근 뒤 상태 재확인

락으로 요청 순서를 세우고, 락을 얻은 다음에 상태를 다시 확인해 중복 처리를 막는다.

| 잠그는 대상 | 위치 | 잠근 뒤 확인하는 것 |
| --- | --- | --- |
| 결제 (order_id) | `payment/service/PaymentService.java:114` 결제 승인 | PAID면 같은 결과로 응답, READY가 아니면 `PAYMENT-006` |
| 결제 (order_id) | `payment/service/PaymentService.java:163` 웹훅 | 이미 처리한 이벤트 ID인지, READY일 때만 상태 전이 |
| 결제 (reservation + PAID) | `payment/service/PaymentService.java:193` 환불 | PAID인 결제만 가져오므로 환불이 두 번 일어나지 않음 |
| 예약 | `reservation/service/ReservationService.java:176` 취소 | PENDING인지 CONFIRMED인지에 따라 처리, 아니면 `RESERVATION-006` |
| 예약 | `reservation/service/ReservationService.java:217` 결제 후 확정 | 이미 CONFIRMED면 true, 취소된 예약이면 false (이후 자동 환불, `PAYMENT-007`) |
| 예약 | `reservation/service/ReservationService.java:254` 선점 만료 | 그 사이 결제가 끝났을 수 있으므로 PENDING이고 기한이 지났는지 다시 확인 |
| 예약 | `reservation/service/ReservationService.java:278` 투숙 완료 | CONFIRMED인지 |
| 숙소 | `accommodation/service/HostAccommodationService.java:105` 공개 전환 | 판매 가능한 객실과 환불정책이 있는지 |
| 숙소 | `accommodation/service/HostAccommodationService.java:142` 객실 판매 중지 | 공개 중인 숙소의 마지막 판매 객실인지 (`ACCOMMODATION-010`) |

결제 승인을 예로 들면 이렇다. 같은 승인 요청 두 개(A, B)가 동시에 들어온다.

1. A가 결제 행을 잠그고, PG 승인 후 `PAID`로 바꾸고 커밋한다.
2. 그동안 B는 락을 기다린다.
3. B가 락을 얻으면 이미 `PAID`이므로 PG를 호출하지 않고 같은 결과를 돌려준다.

락은 순서만 세운다. 실제 중복 판정은 상태 확인이 한다.

숙소 락은 서로 다른 행을 두고 경합하는 문제를 막는다. "LISTED로 공개"와 "마지막 객실 판매 중지"가 동시에 오면, 각자의 검사는 통과하지만 결과적으로 객실 없는 공개 숙소가 생길 수 있다. 그래서 숙소 행 하나를 공통 락으로 잡아 두 요청을 차례로 처리한다.

## 3. 유니크 제약 + 예외 변환 (마지막 방어선)

`exists`로 먼저 검사하더라도, 동시에 들어온 두 요청은 둘 다 그 검사를 통과할 수 있다. 이 경우 DB 유니크 제약이 막고, 서비스가 그 예외를 업무 에러 코드로 바꾼다.

| 위치 | 제약 | 변환되는 에러 |
| --- | --- | --- |
| `user/service/UserService.java:169` | `uk_user_personal_info_email_hash` | `USER-002` 이미 가입된 이메일 |
| `review/service/ReviewService.java:89` | `uk_reviews_reservation` | `REVIEW-004` 이미 리뷰를 작성함 |
| `host/service/HostService.java:48` | `uk_host_businesses_registration_no` | `HOST-003` 이미 등록된 사업자번호 |

예외 변환 없이 제약만 걸어 둔 곳도 있다. 위반되면 500이 나지만, 데이터가 잘못 저장되지는 않는다.

- `uk_payments_order_id`, `uk_payments_payment_key`
- `uk_payment_logs_provider_event` (웹훅 중복)
- `uk_inventory_holds_reservation_inventory`
- `uk_reservations_no`
- `ck_room_inventories_counts` (재고 카운트 음수 방지 CHECK)

## 4. `INSERT IGNORE` (동시 생성을 오류 없이 흡수)

| 위치 | 용도 |
| --- | --- |
| `inventory/repository/RoomInventoryRepository.java:43` | 스케줄러와 호스트 재고 설정이 같은 날짜 행을 동시에 만들 때 |
| `favorite/repository/FavoriteRepository.java:14` | 찜 버튼을 연타해도 한 행만 남게 함 |

재고에서 호스트 특가를 지키는 장치는 `IGNORE`가 아니다. `findDates`로 이미 있는 날짜를 먼저 걸러내는 단계와, upsert가 아닌 INSERT를 쓰는 점이 지킨다. `IGNORE`는 "확인한 시점과 insert 시점 사이"에 다른 트랜잭션이 같은 행을 만든 경우를 처리한다. 이때 중복 키 오류로 트랜잭션 전체가 롤백되지 않고 그냥 넘어가게 한다. 경합이 생겨도 호스트 값이 남는다.

- 스케줄러가 기본가 행을 먼저 만든 경우: 호스트 쪽 insert는 무시되지만, 이어서 행을 다시 읽고 `row.change(...)`로 요청 값을 덮는다.
- 호스트가 특가 행을 먼저 만든 경우: 스케줄러 insert가 무시되어 특가가 그대로 남는다.

찜 삭제도 같은 방향이다. 이미 지워진 찜을 다시 지우면 0행이 지워질 뿐 오류가 나지 않는다.

## 5. 락 순서 고정과 짧은 트랜잭션 (데드락 예방)

| 위치 | 방식 |
| --- | --- |
| `inventory/repository/RoomInventoryRepository.java:58` | 선점할 재고 행을 날짜 오름차순으로 잠금 |
| `inventory/repository/InventoryHoldRepository.java:13` | 확정·해제도 선점과 같은 날짜 오름차순으로 잠금 (주석에 명시) |
| `payment/repository/PaymentRepository.java:20` | 환불 대상 결제를 `order by p.id`로 잠금 |
| `reservation/service/ReservationService.java:253` | 스케줄러가 대상 ID 목록만 먼저 읽고, 예약마다 별도 트랜잭션으로 처리 |
| `reservation/repository/ReservationRepository.java:31` | 스케줄러 대상 ID를 `order by r.id`로 정렬 |

데드락은 서로가 상대가 쥔 락을 기다려 원(cycle)이 생길 때만 일어난다. 한쪽이 기다리기만 하는 것은 단순 대기이고, 앞선 트랜잭션이 끝나면 풀린다.

| | 쥔 락 | 기다리는 락 |
| --- | --- | --- |
| A (1·2·3일, 오름차순) | 1일, 2일 | 3일 |
| B (2·3·4일, 내림차순) | 4일, 3일 | 2일 |

위처럼 잠그는 순서가 서로 반대일 때 데드락이 난다. 둘 다 오름차순이면 겹치는 날 중 가장 앞선 날짜를 먼저 잡은 쪽이 끝까지 진행하고, 다른 쪽은 그 날짜 앞에서 기다린다. 기다리는 시간은 상대의 **예약 생성 트랜잭션**이 끝날 때까지다. 결제를 기다리는 10분이 아니며, 보통 수 밀리초~수십 밀리초다.

## 6. 트랜잭션 경계 분리

| 위치 | 하는 일 |
| --- | --- |
| `payment/service/ReservationCancelledListener.java:20` | `AFTER_COMMIT`: 예약 취소가 커밋된 다음에야 환불을 시작한다. 예약 락과 결제 락을 한 트랜잭션에서 같이 쥐지 않고, PG 호출이 실패해도 취소를 되돌리지 않는다. |
| `payment/service/PaymentService.java:74` | 환불을 `REQUIRES_NEW`(새 트랜잭션)로 처리한다. |
| `payment/service/PaymentService.java:106` | `TransactionTemplate`으로 트랜잭션을 직접 연다. PG 승인이 실패해도 FAILED 상태와 로그는 커밋하고, 에러는 트랜잭션 밖에서 던진다. |

## 7. Redis 원자 연산

| 위치 | 방식 |
| --- | --- |
| `auth/oauth/OAuthStateStore.java:33` | `getAndDelete`: 소셜 로그인 state를 읽으면서 바로 지워, 한 번만 쓰이게 함 |

## 검증 테스트

- `src/test/java/com/staybook/inventory/InventoryTest.java:419`: 재고가 1개인 날짜를 스레드 여러 개로 동시에 선점하면 한 건만 성공하는지 확인한다.
- `src/test/java/com/staybook/inventory/InventoryTest.java:347`: confirm과 release를 두 번 호출해도 결과가 같은지 확인한다.
- `src/test/java/com/staybook/inventory/InventoryTest.java:379`: 여러 날짜 중 하루라도 매진이면 전체가 롤백되는지 확인한다.

---

## 추가 고려사항

### A. 보호 장치가 빠진 곳

아래 네 곳은 엔티티를 읽고 고쳐 쓰는 방식인데, 락도 조건부 UPDATE도 `@Version`도 없다.

| 위치 | 동시 요청 시 생기는 문제 | 대응 방향 |
| --- | --- | --- |
| `auth/service/AuthService.java:80` 로그인 실패 횟수 | 틀린 비밀번호를 동시에 여러 번 보내면 실패 횟수가 덮어써져 덜 올라간다. 계정 잠금을 우회할 수 있다. | 조건부 UPDATE로 증가시키거나 비관적 락 |
| `auth/service/EmailVerificationService.java:51` 인증코드 시도 횟수 | 같은 이유로 최대 시도 횟수 제한을 넘겨 6자리 코드를 계속 대입해 볼 수 있다. | 조건부 UPDATE (`attempt_count < max`) |
| `auth/service/TokenService.java:41` 리프레시 토큰 교체 | 조회와 삭제가 따로 일어난다. 같은 토큰으로 동시에 갱신하면 둘 다 통과해 새 토큰이 두 벌 발급된다. | `getAndDelete`로 원자적으로 소비 |
| `auth/service/PasswordResetService.java:55` 재설정 토큰 | 같은 링크로 동시에 요청하면 둘 다 성공한다. 마지막 비밀번호가 남으므로 피해는 작다. | 조건부 UPDATE로 사용 처리 (`used_at is null`) |

### B. 데드락·락 타임아웃이 실제로 났을 때의 처리

- 데드락이나 락 대기 시간 초과 예외(`CannotAcquireLockException`, `PessimisticLockingFailureException`)를 잡는 코드가 없다. `GlobalExceptionHandler`의 일반 처리로 떨어져 `COMMON-999`(500)로 나간다. 프론트에는 "서버 오류"로 보이지만, 다시 시도하면 대부분 성공한다.
- 재시도(`@Retryable` 등)가 없다.
- 락 대기 시간 설정이 없어 MySQL 기본값(`innodb_lock_wait_timeout` 50초)을 쓴다. 비관적 락에 JPA lock timeout 힌트를 주는 것도 검토할 수 있다.
- 대응 방향: 이 예외들을 전용 코드(예: 409 "잠시 후 다시 시도")로 변환하거나, 멱등한 작업에 한해 짧게 재시도한다.

### C. 락 순서가 확실하지 않은 곳

- 호스트 재고 설정(`InventoryService.setInventories`)은 행을 날짜 오름차순으로 읽는다. 하지만 실제 UPDATE는 JPA 변경 감지로 커밋 시점에 나가므로, 날짜 순서로 나간다고 코드가 보장하지는 않는다. 같은 객실·기간에 예약 선점이 동시에 들어오면 순서가 엇갈릴 여지가 있다. 대응 방향은 두 가지다. 날짜 순 조건부 UPDATE로 바꾸거나, 행마다 명시적으로 flush한다.
- 결제 → 예약 → 재고 순서는 주요 경로에서 일관돼 보인다. 다만 모든 경로를 끝까지 검증하지는 않았다.

### D. 락을 쥔 채 외부 호출

- 결제 승인은 결제 행을 비관적 락으로 잡은 상태에서 PG 승인 HTTP를 호출한다(`PaymentService.doConfirm`). PG 응답이 느리면 그동안 같은 `order_id` 요청이 묶인다. 다른 사용자의 결제에는 영향이 없다.
- 웹훅도 같은 락을 쓰므로, PG 응답 지연이 웹훅 처리 지연으로 이어질 수 있다.
- 대응 방향: 상태를 `IN_PROGRESS`로 바꾸는 짧은 트랜잭션, 락 없는 PG 호출, 결과를 반영하는 짧은 트랜잭션으로 쪼갠다. 다만 복잡도가 올라가므로 PG 지연이 실제 문제가 될 때 검토한다.

### E. `INSERT IGNORE`의 부작용

MySQL `INSERT IGNORE`는 중복 키 오류만 삼키지 않는다. 외래 키 위반, 값 잘림 같은 다른 오류도 경고로 바꿔 넘어간다. 중복 키만 무시하려면 `INSERT ... ON DUPLICATE KEY UPDATE id = id`가 더 좁고 안전하다. 해당 위치는 재고 행 생성과 찜 추가 두 곳이다.

### F. 유니크 제약 위반이 500으로 나가는 곳

결제(`order_id`, `payment_key`), 웹훅 이벤트, 선점 행, 예약 번호의 유니크 위반은 예외 변환이 없어 500으로 나간다. 지금은 앞 단계(락, 이벤트 ID 확인)에서 걸러지므로 정상 흐름에서는 일어나지 않는다. 예약 번호는 무작위 문자열이라 아주 드물게 충돌할 수 있다. 충돌하면 예약 생성이 500으로 실패하므로 재생성 후 재시도를 검토할 수 있다.

### G. 서버를 여러 대 띄울 때

- 스케줄러(선점 만료, 투숙 완료, 재고 자동 생성)가 서버마다 따로 돈다. 지금은 모두 조건부 전이나 `INSERT IGNORE`라서 결과는 같고 일만 중복된다. 중복 실행 자체를 막으려면 ShedLock이 Redisson보다 가볍다.
- 재고와 결제의 동시성은 DB가 판정하므로 서버 대수와 무관하게 안전하다.

### H. Redisson(분산 락) 도입 시점

요구사항 문서(`docs/StayBook_ClaudeCode_요구사항.md`)는 1차 구현에서 Redisson 도입을 금지한다. 현재 코드에는 필요한 곳이 없다. 다음 경우에 검토한다.

1. 쿠폰·선착순 이벤트(2차 기능)처럼 수천 명이 같은 행을 동시에 갱신해 DB 락 대기가 병목이 될 때
2. DB 밖의 자원(외부 API 등)까지 묶어 한 번에 하나씩 처리해야 할 때
3. Redis 캐시를 도입한 뒤, 캐시 만료 순간 여러 요청이 동시에 DB로 몰리는 것(캐시 스탬피드)을 막아야 할 때
4. 부하 테스트로 DB 락 경합이 실제 병목으로 확인됐을 때

새 라이브러리는 요구사항에 따라 이유와 함께 먼저 합의한 뒤 추가한다.
