# Call Flow for IntelliJ

선택한 메소드를 기준으로 **호출(callee)** 과 **피호출(caller)** 관계를 양방향 그래프로 시각화하는 IntelliJ 플러그인입니다. **Kotlin / Java** 를 지원합니다.

- 기준 메소드를 **중심**에 두고, 왼쪽으로 **누가 이 메소드를 호출하는지(caller)**, 오른쪽으로 **이 메소드가 무엇을 호출하는지(callee)** 를 컬럼 + 곡선으로 펼칩니다.
- 노드를 클릭하면 컬럼이 한 단계 더 펼쳐지고, 동시에 해당 메소드 **선언부**로 에디터가 이동합니다.
- 노드 오른쪽(또는 호출선 쪽)의 **↳ 버튼**을 누르면 그 호출이 일어나는 **실제 코드 줄(call-site)** 로 이동합니다.

> UI 는 IDE 내장 브라우저(JCEF)에 HTML/CSS/JS 로 렌더링되며, 호출 관계는 PSI 로 분석해 주입합니다.

## 데모

`examples/demo.html` 를 브라우저로 열면 플러그인과 동일한 UI 를 샘플 데이터로 볼 수 있습니다
(standalone 데모이므로 실제 소스 이동은 IntelliJ 플러그인에서만 동작합니다).

```
   피호출(caller) ↤              ● 기준              → 호출(callee)
 OrderController.createOrder ┐                    ┌ OrderValidator.validate
 BatchImporter.importOrders  ┼─ OrderService ─────┼ PaymentService.pay
 OrderMessageConsumer.onMsg  ┘  .placeOrder       ├ OrderRepository.save
                                                  └ EventPublisher.publish
```

## 사용법

1. 에디터에서 분석할 **메소드 본문 안에 커서**를 둡니다.
2. 우클릭 → **Show Call Flow** (또는 `Ctrl+Alt+F`).
3. 하단 **Call Flow** 툴윈도우에 관계도가 표시됩니다.
   - **노드 왼쪽 영역** 클릭 → 메소드 선언으로 이동 + 그 방향으로 한 단계 더 펼치기
   - **노드의 ↳ 버튼** 클릭 → 실제 호출 코드 줄(call-site)로 이동

## 빌드 & 실행

```bash
# 샌드박스 IDE 로 실행 (최초 1회 IntelliJ SDK 다운로드)
./gradlew runIde

# 배포용 zip 빌드 → build/distributions/*.zip
./gradlew buildPlugin
```

빌드한 zip 은 IntelliJ → **Settings → Plugins → ⚙ → Install Plugin from Disk…** 로 설치할 수 있습니다.

## 구조

```
src/main/kotlin/com/methodflow/
  ShowCallFlowAction.kt         에디터 우클릭 액션 (Ctrl+Alt+F) — 커서 위치 메소드 탐지
  CallGraphAnalyzer.kt          PSI 분석: callee(본문 호출식) + caller(ReferencesSearch),
                                노드 선언 위치 + 호출선(call-site) 위치 수집
  CallFlowPanelService.kt       분석 JSON 을 HTML 에 주입, 노드/호출선 → 소스 이동
  CallFlowToolWindowFactory.kt  JCEF 웹뷰 툴윈도우 + JS↔Kotlin 브리지(JBCefJSQuery)
src/main/resources/
  META-INF/plugin.xml           액션 / 툴윈도우 / 의존성 선언
  web/callflow.html             그래프 UI (그래프·기준 메소드는 플레이스홀더로 주입)
examples/
  demo.html                     샘플 데이터로 채운 standalone 미리보기
```

### 분석 방식 (경량 PSI 휴리스틱)

- **callee**: 메소드 본문의 호출식을 PSI 로 수집한 뒤 `reference.resolve()` 로 선언을 찾습니다.
  전체 타입 추론은 하지 않으므로 일부 확장함수/제네릭은 이름만 잡힐 수 있습니다.
- **caller**: `ReferencesSearch` 로 IDE 인덱스를 그대로 활용합니다(정확).
- 기준 메소드에서 양방향 **2단계**까지 BFS, 노드당 최대 **14가지**
  (`CallGraphAnalyzer.MAX_DEPTH`, `MAX_CHILDREN` 으로 조절).

## 요구 사항

- IntelliJ IDEA **2024.2+** (JCEF 포함 JetBrains Runtime)
- JDK 17

> `runIde` 실행 시 `GradleJvmSupportMatrix ... IllegalArgumentException` 가 보이면,
> 대상 플랫폼의 구버전 JVM 호환성 파서가 최신 JDK(예: 25) 표기를 인식하지 못해 발생하는
> IDE 측 경고입니다. 플러그인 동작과는 무관하며, `build.gradle.kts` 의 대상 IDE 버전을
> 올리면 사라집니다.

## 로드맵

- 노드 클릭 시 그 메소드를 새 기준으로 **무한 드릴다운** (JCEF 브리지 재분석)
- 정확도 강화: Kotlin **Analysis API(K2)** 로 수신자 타입까지 해석
- 깊이/필터(라이브러리 제외, 패키지 한정) 설정 UI

## License

MIT
