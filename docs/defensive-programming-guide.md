# 🛡️ 레이어별 방어적 코딩(Defensive Programming) 실무 가이드 v2 (2026-11-20)

> **방어적 코딩이란?**
> "예상치 못한 비정상 입력, 악의적인 요청, 동시성 충돌, 외부 시스템 장애가 발생하더라도 프로그램이 비정상 종료되거나 **잘못된 데이터를 저장하지 않도록** 미리 방어벽을 세우는 프로그래밍 기법"
>
> 대상 스택: Java 17+ / Spring Boot 3.x (`jakarta.*`) / Spring Data JPA

---

## 📌 v1 대비 변경 사항

### 🔧 내용 정정 (v1의 설명이 실제와 달랐던 부분)
| 항목 | v1 | v2 정정 |
| :--- | :--- | :--- |
| `switch`의 `default` | 항상 `default`에 예외 → 컴파일/런타임에 감지 | enum **switch 식**은 `default`를 **빼야** 새 상수 추가 시 *컴파일 에러*로 잡힘. `default`가 있으면 오히려 조용히 통과 (→ 5-③, javac로 검증) |
| `existsBy...` | "성능 및 동시성 방어" | 성능에는 좋지만 **동시성 방어는 아님** (check-then-act 레이스). 최종 방어는 DB `UNIQUE` + 예외 변환 (→ 2-⑤, 4-②) |
| `findFirstBy...` | 다건이어도 안 터지니 안전 | 데이터 오염을 **은폐**할 수 있음. 유일해야 하는 값은 `UNIQUE`를 걸고 `findBy` 사용 (→ 4-①) |
| `"CONST".equals(x)` | 문자열 상수 먼저 | 가능하면 문자열 대신 **enum 비교(`==`)** 로 (→ 5-①) |
| 컬렉션 반환 예시 | `return stays;` | 내부 리스트를 그대로 노출 → 3-③과 모순. `List.copyOf()` 사용 |

### ➕ 신규 추가
원칙/신뢰 경계(0장) · 트랜잭션·동시성·멱등성(2장) · 도메인 불변식·값 검증(3장) · 쿼리 안전(4장) · 외부 연동 방어(6장) · 안티패턴(7장) · 방어 코드 테스트(8장) · 코드리뷰 질문(9장)

---

## 📑 목차
0. [핵심 원칙: 신뢰 경계와 3중 방어](#0-핵심-원칙-신뢰-경계와-3중-방어)
1. [Controller / Web 레이어](#1-controller--web-레이어-요청-진입점)
2. [Service 레이어](#2-service-레이어-비즈니스-로직)
3. [Domain / Entity 레이어](#3-domain--entity-레이어-도메인-모델)
4. [Repository / DB 레이어](#4-repository--db-레이어-데이터-영속화)
5. [Common / Util 레이어](#5-common--util-레이어-공통-유틸리티)
6. [외부 연동(API·결제·알림) 방어](#6-외부-연동api결제알림-방어)
7. [안티패턴: 방어가 독이 되는 경우](#7-안티패턴-방어가-독이-되는-경우)
8. [방어 코드는 어떻게 테스트하나](#8-방어-코드는-어떻게-테스트하나)
9. [체크리스트 & 코드리뷰 질문](#9-체크리스트--코드리뷰-질문)

---

## 0. 핵심 원칙: 신뢰 경계와 3중 방어

### 신뢰 경계 (Trust Boundary)
**경계 밖에서 들어오는 모든 것은 검증 전까지 신뢰하지 않습니다.**

| 경계 | 불신 대상 | 방어 위치 |
| :--- | :--- | :--- |
| 클라이언트 → 서버 | 요청 바디/파라미터/헤더/파일, **클라이언트가 보낸 ID** | Controller, DTO |
| 서버 → 외부 API | 응답 지연, 장애, 예상 밖 응답 | 외부 연동 계층 |
| 서버 → DB | 동시 요청, 중복, 레거시/오염 데이터 | Repository, DB 제약 |

### 3중 방어: 검증은 "층마다 다른 일"을 한다
같은 검증을 모든 층에서 반복하는 게 아니라, **층마다 책임을 나눕니다.**

| 층 | 검증 대상 | 예시 |
| :--- | :--- | :--- |
| ① 입구 (DTO) | **형식**: 길이, 패턴, 필수값 | `@NotBlank`, `@Size`, `@Pattern` |
| ② 중간 (Service/Domain) | **비즈니스 규칙**: 권한, 상태, 중복, 불변식 | 소유자 확인, 상태 전이, 체크인<체크아웃 |
| ③ 최후 (DB) | **최종 무결성**: 동시 요청에도 깨지지 않는 보장 | `UNIQUE`, `NOT NULL`, `CHECK`, `FK` |

> 💡 **Fail-Fast ≠ 에러 숨기기.** 방어적 코딩의 목표는 "에러가 안 나게" 하는 게 아니라 **"잘못된 상태가 퍼지기 전에 빨리, 명확하게 실패"** 시키는 것입니다. (→ 7장 안티패턴)

---

## 1. Controller / Web 레이어 (요청 진입점)

Controller는 외부 사용자와 가장 먼저 만나는 "성문"입니다. 악의적인 입력과 이상값은 Service로 넘어가기 전에 여기서 차단합니다.

### ① DTO 유효성 검증 (`@Valid` 필수)
* ❌ **위험**: 검증 없이 Service로 전달 / **Entity를 `@RequestBody`로 직접 받음** (클라이언트가 `id`, `status`, `role` 같은 필드를 마음대로 주입하는 Mass Assignment)
* ⭕ **방어**: 요청 전용 DTO + Bean Validation, 사용자 식별은 **인증 정보에서만** 꺼냄

```java
public record HostBusinessRegisterRequest(
    @NotBlank(message = "상호명은 필수입니다.")
    @Size(max = 100, message = "상호명은 100자 이하이어야 합니다.")
    String businessName,

    @NotBlank(message = "사업자등록번호는 필수입니다.")
    @Pattern(regexp = "^[0-9]{10}$", message = "사업자등록번호는 숫자 10자리여야 합니다.")
    @ValidBusinessRegNo                       // 체크섬까지 검증 (아래 커스텀 검증기)
    String businessRegistrationNo,

    @Size(max = 10, message = "이미지는 최대 10장입니다.")   // 리스트는 개수 제한 필수
    List<@NotBlank @Size(max = 500) String> imageUrls
) {}

@PostMapping("/api/host/businesses")
public ApiResponse<Void> registerBusiness(
    @AuthenticationPrincipal Long userId,          // ✅ userId는 body가 아니라 인증 정보에서
    @Valid @RequestBody HostBusinessRegisterRequest request
) {
    hostService.register(userId, request);
    return ApiResponse.ok();
}
```

**Bean Validation 함정 체크**
* `@Size`, `@Pattern`은 **`null`이면 통과**합니다 → 필수값이면 `@NotNull`/`@NotBlank` 병행
* `@NotBlank`는 `String` 전용, 컬렉션은 `@NotEmpty`
* 중첩 객체는 필드에 `@Valid`를 또 붙여야 내부까지 검증
* `@PathVariable`, `@RequestParam` 제약은 클래스에 `@Validated` 필요 (위반 시 `ConstraintViolationException` → 핸들러에서 400 처리)
* 사업자등록번호 같은 **형식 검증은 "존재하는 사업자"를 보장하지 않음** → 실존 여부는 국세청 API 등으로 별도 확인

<details>
<summary>📎 커스텀 검증기 예시: 사업자등록번호 체크섬</summary>

```java
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = BusinessRegNoValidator.class)
public @interface ValidBusinessRegNo {
    String message() default "유효하지 않은 사업자등록번호입니다.";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

public class BusinessRegNoValidator implements ConstraintValidator<ValidBusinessRegNo, String> {
    private static final int[] WEIGHTS = {1, 3, 7, 1, 3, 7, 1, 3, 5};

    @Override
    public boolean isValid(String value, ConstraintValidatorContext ctx) {
        if (value == null) return true;                 // null 여부는 @NotBlank 담당
        if (!value.matches("\\d{10}")) return false;

        int sum = 0;
        for (int i = 0; i < 9; i++) sum += (value.charAt(i) - '0') * WEIGHTS[i];
        sum += ((value.charAt(8) - '0') * 5) / 10;
        int check = (10 - (sum % 10)) % 10;
        return check == value.charAt(9) - '0';
    }
}
```
</details>

### ② 페이징 파라미터 범위 제한
* ❌ **위험**: `?size=10000000` → 메모리 고갈(OOM), `?page=-1` → 예외. 참고로 Spring Data의 `max-page-size` 기본값은 **2000**이라 그대로 두면 충분히 위험합니다.
* ⭕ **방어 A (조용히 보정, Clamping)**

```java
@GetMapping("/api/accommodations")
public ApiResponse<PageResponse<AccommodationSummary>> getList(
    @RequestParam(defaultValue = "0") int page,
    @RequestParam(defaultValue = "10") int size
) {
    int safeSize = Math.max(1, Math.min(size, 50));
    int safePage = Math.max(0, page);
    return ApiResponse.ok(accommodationService.getList(PageRequest.of(safePage, safeSize)));
}
```

* ⭕ **방어 B (전역 설정)** — `Pageable`을 파라미터로 받는 모든 API에 일괄 적용

```yaml
spring:
  data:
    web:
      pageable:
        default-page-size: 10
        max-page-size: 50
```

* ⭕ **방어 C (잘못된 입력은 400으로 거절)** — `@Validated` + `@Min(0) page`, `@Min(1) @Max(50) size`

> **보정 vs 거절?** 외부 공개 API는 거절(400)이 명확하고, 내부/화면용 API는 보정이 편합니다. 팀에서 **하나로 통일**하세요.

**정렬 파라미터도 화이트리스트**: `?sort=password,asc`처럼 임의 필드로 정렬하게 두지 말고 허용 필드(`createdAt`, `price` 등)만 매핑하세요.

### ③ 전역 예외 처리기로 시스템 에러 은닉
* ❌ **위험**: SQL 에러/스택트레이스/클래스명이 응답에 노출 (정보 유출)
* ⭕ **방어**: 비즈니스 예외는 규격화, 알 수 없는 예외는 **로그엔 상세히, 응답엔 통일된 메시지**

```java
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        return ResponseEntity.status(e.getErrorCode().getStatus())
                .body(ApiResponse.fail(e.getErrorCode()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)       // @Valid 실패
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(ApiResponse.fail("INVALID_REQUEST", message));
    }

    @ExceptionHandler({ConstraintViolationException.class,         // @Validated 실패
                       HttpMessageNotReadableException.class,      // JSON 파싱 실패
                       MethodArgumentTypeMismatchException.class}) // 타입 불일치
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception e) {
        return ResponseEntity.badRequest().body(ApiResponse.fail("INVALID_REQUEST", "잘못된 요청입니다."));
    }

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, // 낙관적 락 충돌
                       DataIntegrityViolationException.class})        // UNIQUE 등 위반
    public ResponseEntity<ApiResponse<Void>> handleConflict(Exception e) {
        log.warn("Conflict", e);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail("CONFLICT", "요청이 충돌했습니다. 잠시 후 다시 시도해 주세요."));
    }

    @ExceptionHandler(Exception.class)                              // 마지막 안전망
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception e) {
        log.error("Unhandled exception", e);                        // 상세는 서버 로그에만
        return ResponseEntity.internalServerError()
                .body(ApiResponse.fail("INTERNAL_ERROR", "일시적인 오류가 발생했습니다."));
    }
}
```
> `ApiResponse.fail(...)`은 프로젝트의 응답 클래스에 맞게 조정하세요.

### ④ 인가(Authorization)와 IDOR 방어
* ❌ **위험**: `GET /api/reservations/123` 에서 로그인만 확인하고 **"내 예약인지"** 는 확인하지 않음 (남의 ID를 넣으면 조회됨 = IDOR)
* ⭕ **방어**: 리소스 접근 시 **소유자/역할을 반드시 확인**하고, 권한 없음과 존재하지 않음을 구분해 노출하지 않음 (→ 2-①)
* 역할 기반 접근은 `@PreAuthorize("hasRole('HOST')")` 등으로 URL/메서드 단에서 한 번 더 제한

### ⑤ 파일 업로드 방어
* 용량 제한: `spring.servlet.multipart.max-file-size`, `max-request-size`
* `Content-Type` 헤더는 **위조 가능** → 확장자 화이트리스트 + 필요 시 파일 시그니처(매직 넘버) 확인
* 원본 파일명 그대로 저장 금지 (`../` 경로 탈출) → 서버가 생성한 UUID 파일명 사용

---

## 2. Service 레이어 (비즈니스 로직)

Service는 도메인 규칙을 보장하는 "두뇌"입니다.

### ① 가드 클로즈 (Guard Clauses / Fail-Fast)
비정상 조건을 맨 위에서 즉시 쳐내고, 정상 흐름을 평평하게 유지합니다.

```java
@Transactional
public Long createRoom(Long hostUserId, Long accommodationId, RoomCreateRequest request) {
    // 1. 존재 확인 + 소유권 확인을 한 번에
    Accommodation accommodation = accommodationRepository
            .findByIdAndHostUserId(accommodationId, hostUserId)
            .orElseThrow(() -> new BusinessException(AccommodationErrorCode.NOT_FOUND));

    // 2. 비즈니스 규칙 검증
    if (accommodation.isClosed()) {
        throw new BusinessException(AccommodationErrorCode.CLOSED);
    }

    // 3. 메인 정상 로직
    Room room = Room.create(accommodation, request);
    return roomRepository.save(room).getId();
}
```

> 💡 **"없음"과 "권한 없음"을 구분해서 알려주면** 공격자가 리소스 존재 여부를 알아낼 수 있습니다. 위처럼 `(id, 소유자)`로 조회해 **둘 다 404**로 통일하는 방식이 안전합니다. (v1은 403 분리 — 내부 관리자 화면 등 노출돼도 무방하면 분리해도 됩니다.)

### ② 단건 조회는 `Optional` + `orElseThrow`
```java
HostBusiness business = hostBusinessRepository.findById(businessId)
        .orElseThrow(() -> new BusinessException(HostErrorCode.BUSINESS_NOT_FOUND));
```
* ❌ `.get()` 직접 호출 (값이 없으면 `NoSuchElementException`, 의미 없는 에러)
* ❌ `Optional`을 필드/메서드 파라미터로 사용, `Optional`을 반환하는 메서드가 `null` 반환
* ✅ `Optional`은 **반환 타입**에서만, 꺼낼 땐 `orElseThrow(의미 있는 예외)`

### ③ 상태 전이(State Transition) 검증
허용 가능한 전이를 **enum 한 곳에 정의**하면 서비스 곳곳의 `if`가 사라집니다.

```java
public enum ReservationStatus {
    PENDING, CONFIRMED, CANCELLED, COMPLETED;

    public boolean canTransitionTo(ReservationStatus next) {
        return switch (this) {                       // default 없음 → 새 상태 추가 시 컴파일 에러
            case PENDING   -> next == CONFIRMED || next == CANCELLED;
            case CONFIRMED -> next == COMPLETED || next == CANCELLED;
            case CANCELLED, COMPLETED -> false;
        };
    }
}

// Entity
public void cancel() {
    if (this.status == ReservationStatus.CANCELLED) {
        throw new BusinessException(ReservationErrorCode.ALREADY_CANCELLED);   // 멱등 처리가 필요하면 여기서 return
    }
    if (!this.status.canTransitionTo(ReservationStatus.CANCELLED)) {
        throw new BusinessException(ReservationErrorCode.INVALID_STATUS_TRANSITION);
    }
    this.status = ReservationStatus.CANCELLED;
}
```

### ④ 트랜잭션 경계 방어
| 위험 | 방어 |
| :--- | :--- |
| 외부 API/메일/결제 호출을 `@Transactional` 안에서 수행 → 응답 지연 동안 DB 커넥션 점유, 롤백 시 외부 호출만 남음 | 외부 호출은 **트랜잭션 밖**으로 분리하거나 커밋 후 이벤트(`@TransactionalEventListener(AFTER_COMMIT)`)로 처리 |
| 조회 전용 메서드에 쓰기 트랜잭션 | `@Transactional(readOnly = true)` |
| 같은 클래스 내부 호출(`this.method()`)은 프록시를 안 거쳐 `@Transactional`이 **무시됨** | 별도 빈으로 분리 |
| Checked Exception은 기본적으로 롤백되지 않음 | `rollbackFor` 지정 또는 `RuntimeException` 기반 `BusinessException` 사용 |
| `catch`로 예외를 삼키면 롤백이 안 일어남 | 삼키지 말고 재던지기 |

### ⑤ 동시성 방어 (중복 가입·이중 예약·재고)
> ⚠️ **"조회 후 검사 후 저장(check-then-act)"은 동시 요청에서 반드시 뚫립니다.** 두 요청이 동시에 `exists = false`를 보고 둘 다 저장하기 때문입니다.

**방법 1. DB UNIQUE + 예외 변환 (중복 방지의 기본)**
```java
@Transactional
public Long register(Long userId, HostBusinessRegisterRequest req) {
    // 1차: 빠른 사전 검사 → 사용자에게 친절한 에러 (동시성 방어는 아님!)
    if (hostBusinessRepository.existsByBusinessRegistrationNo(req.businessRegistrationNo())) {
        throw new BusinessException(HostErrorCode.DUPLICATE_REGISTRATION_NO);
    }
    try {
        // 2차: saveAndFlush로 즉시 INSERT → UNIQUE 위반이 이 try 안에서 터지게 함
        return hostBusinessRepository.saveAndFlush(
                HostBusiness.createApproved(userId, req.businessName(), req.businessRegistrationNo())
        ).getId();
    } catch (DataIntegrityViolationException e) {
        // 동시 요청이 1차 검사를 통과한 경우 DB가 최종 차단
        throw new BusinessException(HostErrorCode.DUPLICATE_REGISTRATION_NO);
    }
}
```

**방법 2. 낙관적 락 — 충돌이 드문 수정**
```java
@Version
private Long version;      // 동시에 수정하면 한쪽이 ObjectOptimisticLockingFailureException → 409
```

**방법 3. 비관적 락 — 충돌이 잦은 재고/객실 차감**
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))  // 무한 대기 방지
@Query("select r from Room r where r.id = :id")
Optional<Room> findByIdForUpdate(@Param("id") Long id);
```

### ⑥ 멱등성 (Idempotency)
결제·예약처럼 **네트워크 재시도로 두 번 실행되면 안 되는 요청**은 클라이언트가 `Idempotency-Key`를 보내고, 서버가 `(key)`에 UNIQUE를 걸어 두 번째 요청에는 **처음 결과를 그대로 반환**하게 합니다.

---

## 3. Domain / Entity 레이어 (도메인 모델)

Entity는 데이터 무결성이 절대 깨지지 않도록 지키는 곳입니다.

### ① Setter 금지 & 기본 생성자 잠금
* ❌ `public` 기본 생성자 + `setXxx()` → 어디서든 빈 객체 생성/필드 변조. `@Data`, `@Setter`도 Entity에는 금지
* ⭕ `@NoArgsConstructor(access = PROTECTED)` + 정적 팩토리 메서드 + **의미 있는 변경 메서드** (`cancel()`, `approve()`)

### ② 생성 시점에 불변식 검증
"만들어지는 순간부터 항상 유효한 객체"를 보장합니다.

```java
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HostBusiness {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String businessName;

    @Version
    private Long version;

    public static HostBusiness createApproved(Long hostUserId, String name, String regNo) {
        Objects.requireNonNull(hostUserId, "hostUserId must not be null");
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new IllegalArgumentException("상호명이 올바르지 않습니다.");
        }
        if (regNo == null || !regNo.matches("\\d{10}")) {
            throw new IllegalArgumentException("사업자등록번호가 올바르지 않습니다.");
        }
        HostBusiness business = new HostBusiness();
        business.hostUserId = hostUserId;
        business.businessName = name;
        business.businessRegistrationNo = regNo;
        business.approvalStatus = ApprovalStatus.APPROVED;
        return business;
    }
}
```
> **예외 종류를 구분하세요.** DTO 검증 실패 = 사용자 실수(400). 도메인 불변식 위반 = Controller/Service 검증을 빠져나온 **프로그래밍 오류** 가능성 → `IllegalArgumentException`(→ 500 + 에러 로그)으로 던져 버그를 드러냅니다.

### ③ 컬렉션 필드 방어 (복사 vs 뷰)
```java
@Getter(AccessLevel.NONE)               // Lombok @Getter가 만드는 원본 리스트 노출 차단
private List<RoomImage> images = new ArrayList<>();

public List<RoomImage> getImages() {
    return List.copyOf(images);          // 스냅샷 복사 (null 요소 불가)
    // 또는 Collections.unmodifiableList(images)  → 복사 비용 없는 읽기 전용 "뷰"
}

public void addImage(RoomImage image) { // 변경은 도메인 메서드로만, 규칙도 여기서
    if (images.size() >= 10) throw new BusinessException(RoomErrorCode.TOO_MANY_IMAGES);
    images.add(image);
}
```

### ④ Enum은 항상 `EnumType.STRING`
* ❌ 기본값 `ORDINAL`은 enum 순서를 바꾸는 순간 기존 데이터 전체가 오염
* ⭕ `@Enumerated(EnumType.STRING)` + 길이 지정 (DB `CHECK`까지 걸면 더 확실)

### ⑤ 값 자체의 위험 방어
| 대상 | ❌ 위험 | ⭕ 방어 |
| :--- | :--- | :--- |
| 금액 | `double`/`float` 오차 | `BigDecimal`(또는 정수 원 단위), 음수 금지 |
| 날짜 범위 | 체크인 ≥ 체크아웃, 과거 날짜 | 생성자/팩토리에서 `checkIn.isBefore(checkOut)` 검증 |
| 현재 시간 | `LocalDateTime.now()` 직접 호출 → 테스트 불가 | `Clock` 주입 |
| 연관 엔티티 | 양방향 관계 한쪽만 설정 | 연관관계 편의 메서드 하나로 양쪽 동기화 |

---

## 4. Repository / DB 레이어 (데이터 영속화)

### ① 단건 조회: 다건이 나올 수 있다면 "원인"부터 막으세요
| 상황 | 올바른 선택 |
| :--- | :--- |
| 비즈니스상 **유일해야** 하는 값 (사업자번호, 이메일) | DB에 `UNIQUE` + `findBy...` (중복이 생기면 **드러나야** 함) |
| 한 사용자가 여러 건 가질 수 있고 "그중 첫 번째"가 의미 있음 | `findFirstBy...OrderByIdAsc` (**정렬 기준을 명시**해야 결과가 결정적) |

```java
Optional<HostBusiness> findFirstByHostUserIdOrderByIdAsc(Long hostUserId);
```
> ⚠️ 유일해야 하는 값에 `findFirstBy`를 쓰면 `NonUniqueResultException`은 사라지지만 **중복 데이터가 있다는 사실도 함께 사라집니다.** 에러를 없애는 게 아니라 문제를 숨기는 셈입니다.

### ② 중복 검사: `existsBy` + DB 제약의 조합
```java
boolean existsByBusinessRegistrationNo(String registrationNo);   // 전체 엔티티 로딩 없이 가볍게 확인
```
* `existsBy`는 **성능 최적화 + 친절한 에러 메시지용**입니다.
* 동시 요청을 막는 건 ③의 `UNIQUE` 제약이며, 위반 예외를 변환하는 코드는 **2-⑤** 참고.

### ③ DB 제약조건 — 최후의 보루
애플리케이션 검증이 동시 요청·버그·직접 SQL로 뚫려도 **DB가 마지막에 막습니다.**

```sql
CONSTRAINT uk_host_businesses_registration_no UNIQUE (business_registration_no),
CONSTRAINT ck_reservations_dates CHECK (check_in_date < check_out_date),
CONSTRAINT ck_rooms_price        CHECK (price >= 0),
CONSTRAINT fk_rooms_accommodation FOREIGN KEY (accommodation_id) REFERENCES accommodations (id)
```
* 컬럼은 기본 `NOT NULL`로 시작하고 꼭 필요할 때만 NULL 허용
* `CHECK`는 MySQL 8.0.16 이상에서 실제로 강제됨 (이전 버전은 무시)
* 스키마는 `ddl-auto: update` 대신 **Flyway/Liquibase 마이그레이션**으로 관리해 제약이 환경마다 빠지지 않게

### ④ 쿼리 안전
| 위험 | 방어 |
| :--- | :--- |
| SQL Injection | `@Query`/JdbcTemplate에서 **문자열 연결 금지**, 항상 파라미터 바인딩(`:name`, `?`) |
| `IN` 절에 **빈 리스트** → DB/드라이버에 따라 에러 | 호출 전에 비어 있으면 빈 결과를 바로 반환 |
| 대량 조회로 OOM | 목록 API는 항상 `Pageable`, 배치는 chunk 단위 |
| N+1 쿼리 | `fetch join` / `@EntityGraph` / `default_batch_fetch_size` |
| **컬렉션 fetch join + 페이징** | 메모리 페이징 경고(HHH000104) 발생 → `batch_fetch_size` 사용 |
| 정렬 필드를 사용자 입력 그대로 사용 | 허용 필드 화이트리스트 (1-② 참고) |

---

## 5. Common / Util 레이어 (공통 유틸리티)

### ① `null`-safe 비교
```java
// ❌ target이 null이면 NPE
if (target.equals("APPROVED")) { ... }

// ⭕ null-safe
if ("APPROVED".equals(target)) { ... }

// ⭐ 더 좋음: 문자열 대신 enum을 쓰고 == 로 비교 (오타 방지 + null-safe)
if (status == ApprovalStatus.APPROVED) { ... }

// 둘 다 null일 수 있는 일반 객체
if (Objects.equals(a, b)) { ... }
```
* `BigDecimal`은 `equals`가 **스케일까지 비교**(`2.0` ≠ `2.00`) → 값 비교는 `compareTo(...) == 0`
* 메서드 진입부의 필수 인자는 `Objects.requireNonNull(x, "x must not be null")`로 일찍 실패

### ② 컬렉션 반환 시 `null` 금지 + 내부 컬렉션 노출 금지
```java
// ❌ 호출자마다 null 체크 강제
public List<Accommodation> findStays() {
    if (stays.isEmpty()) return null;
    return stays;
}

// ⭕ 항상 빈 컬렉션 + 방어적 복사
public List<Accommodation> findStays() {
    return List.copyOf(stays);          // 비어 있으면 빈 불변 리스트
}
```
* 불변 리스트(`List.of`, `List.copyOf`)는 `add()` 호출 시 `UnsupportedOperationException` → 호출자가 수정해야 하면 `new ArrayList<>(...)`
* `Optional`을 반환하는 메서드도 `null`을 반환하지 말 것 (`Optional.empty()`)

### ③ `switch`와 `default` — ⚠️ v1에서 정정된 부분
enum에 대한 **switch 식(expression)** 은 컴파일러가 *모든 상수를 다뤘는지* 검사합니다. 이때 `default`를 쓰면 **이 검사가 꺼집니다.**

```java
enum ApprovalStatus { PENDING, APPROVED, REJECTED, EXPIRED }   // EXPIRED 새로 추가!

// ⭕ default 없음 → EXPIRED 분기가 없으면 "컴파일 에러" 로 즉시 발견
String label(ApprovalStatus s) {
    return switch (s) {
        case PENDING  -> "심사 대기";
        case APPROVED -> "승인 완료";
        case REJECTED -> "반려";
        // error: the switch expression does not cover all possible input values
    };
}

// ❌ default 있음 → 컴파일 통과, EXPIRED가 들어오면 "런타임에" 예외 (배포 후 발견)
String label(ApprovalStatus s) {
    return switch (s) {
        case PENDING  -> "심사 대기";
        case APPROVED -> "승인 완료";
        case REJECTED -> "반려";
        default -> throw new IllegalArgumentException("지원하지 않는 상태: " + s);
    };
}
```
| 문법 | 권장 |
| :--- | :--- |
| enum **switch 식** (`->` + 값 반환) | **`default` 생략** (컴파일 타임 검증) |
| 예전 **switch 문** (`case X:`), enum이 아닌 `int`/`String` switch | `default`에서 예외 던지기 |

### ④ 숫자·시간 방어
* 금액/수량 연산에 오버플로 가능성이 있으면 `Math.addExact`, `multiplyExact`, `toIntExact` (오버플로 시 예외)
* 문자열 → 숫자 변환(`Integer.parseInt`)은 `NumberFormatException` 처리 필수 (사용자 입력이면 DTO 단계에서 타입으로 받기)
* 서버 저장 시각은 UTC(`Instant`) 기준으로 통일하고, 표시할 때만 변환

### ⑤ 로그 방어
* 비밀번호, 토큰, 주민/사업자번호, 카드번호 등 **민감정보는 로그에 남기지 않거나 마스킹**
* 요청 단위 추적 ID(MDC)를 남겨 "사용자에겐 일반 메시지, 개발자에겐 상세 로그"를 연결
* 사용자 입력을 그대로 로그에 찍을 때 개행 문자 제거 (로그 위조 방지)

---

## 6. 외부 연동(API·결제·알림) 방어

외부 시스템은 **언젠가 반드시 느려지거나 죽습니다.** 내 서버가 함께 죽지 않게 설계합니다.

| 위험 | 방어 |
| :--- | :--- |
| 응답이 안 와서 스레드 무한 대기 | **연결/읽기 타임아웃 필수** (기본값이 무한인 클라이언트가 많음) |
| 일시적 장애 | **재시도는 멱등한 요청에만**, 지수 백오프 + 최대 횟수 제한 |
| 장애 전파 (연쇄 실패) | 서킷 브레이커(Resilience4j 등), 실패 시 대체 응답(Fallback) |
| 예상 밖 응답(필드 누락, null) | 외부 응답 DTO도 **검증**한 뒤에 사용, 알 수 없는 필드는 무시 설정 |
| 결제 승인 후 내 DB 저장 실패 | 멱등 키 + 보상 트랜잭션(취소 API) / 상태를 `PENDING`으로 먼저 저장 후 확정 |
| API 키·시크릿 코드 하드코딩 | 환경변수/시크릿 매니저, 저장소에 커밋 금지 |
| 웹훅 위조 | 서명 검증, 재전송 공격 방지(타임스탬프/ID 중복 체크) |

---

## 7. 안티패턴: 방어가 독이 되는 경우

방어적 코딩을 "에러를 안 나게 하는 것"으로 오해하면 오히려 버그가 더 깊이 숨습니다.

| ❌ 안티패턴 | 왜 위험한가 | ⭕ 대신 |
| :--- | :--- | :--- |
| `catch (Exception e) { }` 빈 catch | 실패가 사라지고 데이터가 반쯤만 저장됨 | 처리할 수 없으면 **던지기**, 최소 로그 + 재던지기 |
| `null`이면 조용히 기본값(`0`, `""`)으로 대체 | 잘못된 데이터가 정상처럼 저장·집계됨 | 필수값이면 **즉시 예외**, 기본값은 명시된 규칙일 때만 |
| 모든 메서드마다 `if (x == null)` 도배 | 코드 소음, 진짜 위험한 곳이 묻힘 | 신뢰 경계(입구)에서 한 번 검증하고 내부는 `non-null` 계약으로 |
| 모든 층에서 같은 검증 반복 | 규칙 변경 시 여러 곳 수정, 불일치 발생 | 0장의 **3중 방어 책임 분리** |
| 예외 메시지에 내부 정보(SQL, 경로) 노출 | 정보 유출 | 응답은 일반 메시지, 상세는 로그 |
| `Optional.get()`, `Optional`을 필드로 사용 | NPE를 다른 예외로 바꿨을 뿐 | `orElseThrow(의미 있는 예외)` |
| 클라이언트 검증만 믿음 (프론트 `required` 등) | API 직접 호출로 우회 | **서버 검증은 항상 필수** |
| 방어 로직을 테스트하지 않음 | 방어가 실제로 작동하는지 모름 | 8장 |

---

## 8. 방어 코드는 어떻게 테스트하나

"정상 케이스"만 테스트하면 방어 코드는 한 번도 실행되지 않습니다.

### 경계값·이상값 체크 표 (DTO/도메인 테스트)
| 입력 종류 | 테스트할 값 |
| :--- | :--- |
| 문자열 | `null`, `""`, `"   "`, 최대 길이, **최대 길이 + 1**, 특수문자/이모지 |
| 숫자 | `0`, `-1`, `Integer.MAX_VALUE`, 소수점 |
| 컬렉션 | `null`, 빈 리스트, 최대 개수 + 1, 요소 중 `null` |
| 페이징 | `page=-1`, `size=0`, `size=100000` |
| 상태 | 허용되지 않는 전이 (취소된 예약을 다시 취소/완료 처리) |
| 권한 | 타인 소유 리소스 접근, 비로그인, 다른 역할 |

### 동시성 테스트: "100명이 동시에 같은 번호로 등록하면 1건만 성공"
```java
@Test
void 동시에_같은_사업자번호로_등록하면_한건만_성공한다() throws Exception {
    int threads = 20;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger success = new AtomicInteger();
    AtomicInteger duplicate = new AtomicInteger();

    for (int i = 0; i < threads; i++) {
        long userId = i + 1;
        pool.submit(() -> {
            ready.countDown();
            try {
                start.await();                                    // 동시에 출발
                hostService.register(userId, requestWith("2208162517"));
                success.incrementAndGet();
            } catch (BusinessException e) {
                duplicate.incrementAndGet();
            } catch (InterruptedException ignored) { }
        });
    }
    ready.await();
    start.countDown();
    pool.shutdown();
    pool.awaitTermination(10, TimeUnit.SECONDS);

    assertThat(success.get()).isEqualTo(1);
    assertThat(duplicate.get()).isEqualTo(threads - 1);
    assertThat(hostBusinessRepository.count()).isEqualTo(1);
}
```
> 이 테스트는 **`@Transactional` 테스트 롤백을 쓰지 않고** 실제 커밋이 일어나게 해야 의미가 있습니다. `existsBy`만 있고 DB `UNIQUE`가 없으면 이 테스트는 실패합니다 — 그게 바로 2-⑤의 요점입니다.

### 그 외
* `@WebMvcTest` + MockMvc로 잘못된 요청이 **400** 으로 응답되는지, 응답 본문에 스택트레이스가 없는지 확인
* 새 enum 상수 추가 시 깨지도록 3-③/5-③의 `default` 없는 `switch`를 활용

---

## 9. 체크리스트 & 코드리뷰 질문

### 레이어별 체크리스트
| 레이어 | 방어 포인트 | 핵심 키워드 |
| :--- | :--- | :--- |
| **Controller** | 입력 형식 차단, 페이징·정렬 제한, 인가(IDOR), 업로드 제한, 에러 은닉 | `@Valid`/`@Validated`, `max-page-size`, `@RestControllerAdvice`, 소유자 확인 |
| **Service** | Fail-Fast, `Optional` 예외 매핑, 상태 전이, 트랜잭션 경계, 동시성·멱등성 | `orElseThrow`, `canTransitionTo`, `readOnly`, `@Version`, `Idempotency-Key` |
| **Domain** | 생성 시 불변식, Setter 차단, 컬렉션 보호, Enum 문자열 | `PROTECTED`, 정적 팩토리, `List.copyOf`, `EnumType.STRING` |
| **Repository/DB** | 유일성은 DB가 보장, 가벼운 존재 확인, 쿼리 안전, 제약조건 | `UNIQUE`, `CHECK`, `existsBy`, 파라미터 바인딩, N+1 |
| **Common/Util** | NPE 차단, 빈 컬렉션 반환, 컴파일 타임 enum 검증, 민감정보 로그 제외 | `Objects.requireNonNull`, enum `==`, `default` 없는 switch 식 |
| **외부 연동** | 타임아웃, 재시도(멱등만), 서킷 브레이커, 응답 검증, 시크릿 관리 | timeout, backoff, fallback |

### PR 리뷰할 때 던질 질문 7가지
1. 이 입력이 `null`, 빈 값, 아주 큰 값이면 어떻게 되는가?
2. 같은 요청이 **동시에 두 번** 오면 어떻게 되는가? (DB 제약이 받쳐주는가?)
3. 이 API를 **남의 ID**로 호출하면 막히는가?
4. 이 예외가 사용자 응답에 **내부 정보**를 노출하지 않는가?
5. 이 `catch`는 에러를 **삼키고** 있지 않은가?
6. 외부 호출에 **타임아웃**이 있고, 트랜잭션 안에서 호출하고 있지 않은가?
7. 새 enum 상수/상태가 추가되면 **컴파일러나 테스트가 알려주는가?**
