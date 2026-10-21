# PACKAGING.md — Windows 실행 방식 (CMD 스크립트, jpackage app-image)

## 현재 (Phase 1.5): javaw 백그라운드 실행

| 파일 | 역할 |
|---|---|
| `MentalCare 시작.cmd` | 더블클릭 → `scripts\start-background.ps1`을 숨김 창으로 실행하고 즉시 닫힘 |
| `MentalCare 종료.cmd` | 더블클릭 → `scripts\stop.ps1` |
| `scripts\start-background.ps1` | `.env` 로드 → `javaw.exe -jar backend\target\mentalcare.jar` (콘솔 없음) → `/api/health`가 UP이 될 때까지 대기(최대 120초) → 기본 브라우저로 `http://127.0.0.1:8080` |
| `scripts\stop.ps1` | `POST /api/system/shutdown`(정상 종료: SQLite WAL checkpoint, 커넥션 정리) → 15초 내 종료 안 되면 `Stop-Process -Force` |
| `.run\mentalcare.pid`, `.run\mentalcare.log` | PID와 애플리케이션 로그 (git/docker 제외) |

중복 실행 방지: 시작 절차 전체를 저장소별 named Mutex로 직렬화한 뒤 (1) PID 파일의 프로세스가 아직 **이 저장소의 jar 절대경로**를 실행 중이면 재사용, (2) PID 파일이 없어도 같은 절대경로의 jar를 실행 중인 java/javaw가 있으면 재사용, (3) 포트를 다른 프로그램이 쓰고 있으면 오류 창, (4) 준비 확인 시 포트에 응답한 PID가 우리가 띄운 PID와 같은지 대조. PID 파일에는 PID·포트·jar 경로를 기록하고, `-Port`를 바꾸면 OAuth 복귀 주소(`openai.siwc.public-base-url`)도 함께 넘깁니다. 종료 스크립트는 같은 절대경로 조건을 쓰므로 다른 복사본이나 `mentalcare.jar.backup` 같은 이름은 대상이 아닙니다.

데이터 영향: 스크립트는 `MENTALCARE_DATA_DIR`(기본 `<repo>\data`)와 `JAVA_HOME`만 환경변수로 넘깁니다. 상담 DB, Markdown, `credentials/`는 읽거나 쓰지 않습니다. 정상 종료 경로를 먼저 쓰므로 강제 종료보다 안전하며, 강제 종료가 되더라도 SQLite WAL과 credential의 원자적 쓰기 덕분에 손상되지 않습니다.

## jpackage app-image (`MentalCare.exe`) — 적용됨

설치 프로그램이 아닌 **portable app-image**만 만듭니다. 위 CMD 방식은 그대로 유지되며 이것은 추가 실행 방식입니다.

```
scripts\package-windows.cmd   →   dist\MentalCare\MentalCare.exe  (+ app\, runtime\)
```

| 단계 | 내용 |
|---|---|
| 1 | 실행 중인 `dist\MentalCare\MentalCare.exe`가 있으면 중단 (exe가 잠겨 교체 불가) |
| 2 | 기존 `dist\MentalCare` 안에 DB·`.env`·credential·PID/로그·data 폴더가 있으면 중단 (이미지는 통째로 지워지므로) |
| 3 | `scripts\build.cmd`: frontend build → `mvn clean package` → `mentalcare.jar`(기존 fat jar, 변경 없음) + `mentalcare-launcher.jar` |
| 4 | `backend\target\jpackage-input\`에 두 jar만 복사 (`--input`에 실제 데이터가 들어갈 경로가 없음) |
| 5 | `jpackage --type app-image --name MentalCare --app-version 0.2.0 --input backend\target\jpackage-input --main-jar mentalcare-launcher.jar --main-class com.mentalcare.launcher.MentalCareLauncher --java-options -Dfile.encoding=UTF-8 --dest dist` |
| 6 | 이미지와 두 jar 안에 개인 데이터가 없는지 검사, 하나라도 있으면 실패 |

- `--add-modules`를 지정하지 않습니다. jpackage 기본 모듈 집합은 JDK 전체 모듈(jdk.charsets, jdk.localedata, jdk.crypto.mscapi 포함)이라 CMD 방식이 쓰는 JDK와 동작이 같습니다. 디버그 정보와 `java.exe` 등 명령은 제거되어 약 190 MB입니다. (`ALL-DEFAULT`는 jpackage에서 확장되지 않아 `java.base`만 남으므로 쓰지 않습니다.)
- `--win-console`을 쓰지 않으므로 exe는 GUI subsystem입니다(콘솔 창 없음).
- 아이콘은 jpackage 기본 아이콘입니다.

### launcher 구조

`MentalCareApplication`과 fat jar의 `Start-Class`는 그대로 두고, JDK만 쓰는 작은 클래스 `com.mentalcare.launcher.MentalCareLauncher`를 별도 jar(`mentalcare-launcher.jar`, maven-jar-plugin의 `launcher` classifier)로 만들었습니다. 이 클래스가 `start-background.ps1`의 역할을 Java로 옮긴 것입니다.

```
MentalCare.exe (jpackage 런처; JVM은 같은 exe의 자식 프로세스에서 실행)
 └ MentalCareLauncher.main
    1. 데이터 위치 결정 (아래 표) → 쓰기 가능 확인, 이미지 내부면 거부
    2. .run\launcher.lock 획득 (동시 더블클릭 직렬화, 시작하는 동안만 보유)
    3. GET http://127.0.0.1:8080/api/health
         {"status":"UP","app":"mentalcare"}  → 브라우저만 열고 종료 (새 백엔드 없음)
         다른 응답/포트 점유                 → 5초 재확인 후 오류 창, 아무것도 종료하지 않음
         비어 있음                            → 4
    4. org.springframework.boot.loader.launch.JarLauncher.main(...)  ← java -jar mentalcare.jar와 같은 경로, 같은 프로세스
         --server.address=127.0.0.1 --server.port=8080 --mentalcare.data-dir=<data> --logging.file.name=<run>\mentalcare.log
    5. /api/health 확인 → Desktop.browse(http://127.0.0.1:8080) (실패 시 rundll32 url.dll)
    6. main 반환. Tomcat 스레드가 프로세스를 유지 → 종료는 POST /api/system/shutdown
```

- **같은 프로세스에서 실행하는 이유**: 자식 `javaw.exe`를 띄우면 PID 파일·프로세스 감시·강제 종료가 다시 필요하지만, 같은 프로세스면 exe 자체가 백엔드이므로 PID 관리가 필요 없고 jpackage가 `java.exe`를 넣지 않아도 됩니다.
- Spring Boot는 `java.awt.headless`가 비어 있으면 `true`로 설정하므로 launcher가 먼저 `false`로 둡니다(브라우저 열기와 오류 창용). 백엔드는 AWT를 쓰지 않습니다.
- `.env`는 home 폴더(아래 표)의 것을 읽어 **시스템 속성**으로 넣습니다. `application.yml`의 `${OPENAI_AUTH_MODE:...}` 같은 placeholder는 그대로 해석되지만, placeholder가 없는 키를 relaxed binding으로 바꾸는 용도(예: `LOGGING_LEVEL_ROOT`)는 실제 환경변수로 지정해야 합니다.
- 포트는 8080 고정입니다(OAuth 복귀 주소). 다른 포트가 필요하면 CMD 방식의 `-Port`를 쓰세요.
- 오류 창(항상 위): 포트 충돌, 시작 실패(로그 경로 안내), 120초 내 미준비(계속 대기), 데이터 폴더 생성/쓰기 실패, 데이터 폴더가 이미지 내부, 이미 실행 중인 MentalCare가 다른 데이터 폴더를 쓰는 경우(안내 후 그 인스턴스를 엶).
- 종료 코드: 0 정상/이미 실행 중, 2 포트 충돌, 5 시작 실패, 6 같은 데이터 폴더를 다른 MentalCare가 사용 중, 10 데이터 위치 없음, 11 이미지 내부 경로, 12 데이터 폴더 쓰기 불가.

### 데이터 위치

| 조건 (위에서부터) | home | 데이터 | 로그/lock |
|---|---|---|---|
| exe가 `<repo>\dist\MentalCare\` (repo = `backend\pom.xml`과 `scripts\start-background.ps1`이 있는 폴더) | `<repo>` | `<repo>\data` | `<repo>\.run\` |
| 이미지 옆에 `MentalCare-data\data`가 이미 있음 | `MentalCare-data` | `MentalCare-data\data` | `MentalCare-data\.run\` |
| `%LOCALAPPDATA%\MentalCare\data`가 이미 있음 | `%LOCALAPPDATA%\MentalCare` | `...\data` | `...\.run\` |
| 이미지 옆에 쓸 수 있음 | `MentalCare-data` (새로 만듦) | | |
| 그 외 | `%LOCALAPPDATA%\MentalCare` (새로 만듦) | | |

`MENTALCARE_DATA_DIR`(home의 `.env` > 환경변수)이 있으면 데이터 폴더만 그 경로로 바뀝니다. 상대경로는 home 기준으로 해석합니다(exe 폴더 기준이면 image 안이 되어 거부되므로). 기존 데이터가 쓰던 위치를 우선하므로 쓰기 가능 여부가 바뀌어도 데이터 위치가 바뀌지 않습니다.

평가: 저장소에서 빌드해 그대로 쓰는 현재 사용 방식에서는 **기존 `<repo>\data`를 그대로 쓰는 것**이 가장 안전합니다(마이그레이션 없음, CMD 방식과 같은 데이터, 이미 로그인된 credential 유지). 복사해 쓰는 경우에는 이미지 옆 폴더가 "폴더째 옮기면 데이터도 함께"라는 portable의 기대와 맞습니다. `%LOCALAPPDATA%`는 이미지 옆에 쓸 수 없는 경우의 대안이며, 설치형(MSI)을 만들 때 기본값 후보입니다.

`app\`·`runtime\`에는 어떤 경우에도 저장하지 않습니다: 이미지는 다시 빌드할 때 통째로 교체됩니다.

### 기존 스크립트와의 호환

- `start-background.ps1`/`stop.ps1`은 이 저장소의 `dist\MentalCare\MentalCare.exe`(실행 파일 절대경로)도 "우리 프로세스"로 인식합니다. 다른 위치에 복사한 exe는 대상이 아닙니다.
- jpackage 런처는 같은 exe를 자식 프로세스로 한 번 더 띄워 그 안에서 JVM을 실행합니다. 포트를 가진 것은 자식이므로 `start-background.ps1`은 부모가 아닌 자식을 기존 인스턴스로 고릅니다. `stop.ps1`은 둘 다 대상으로 잡고 `POST /api/system/shutdown`으로 정상 종료합니다(자식이 끝나면 부모도 끝남).
- exe가 실행 중일 때 `MentalCare 시작.cmd` → 브라우저만 엶. CMD 인스턴스가 실행 중일 때 `MentalCare.exe` → 브라우저만 엶.
- 복사본 exe는 앱 안의 종료 버튼으로 종료합니다.

### 데이터 폴더당 프로세스 하나

포트가 아니라 데이터 폴더를 기준으로 한 번에 하나만 실행됩니다. 백엔드(Spring Boot)가 최종 데이터 폴더를 정한 직후, DB와 웹 서버를 열기 전에 `<data>\mentalcare.lock`에 OS 수준 배타 잠금(`FileChannel.tryLock`)을 잡고 프로세스가 끝날 때까지 유지합니다(`DataDirectoryLock`).

- EXE, CMD(`-Port` 포함), `java -jar` 모두 같은 백엔드 코드를 거치므로 똑같이 적용됩니다. 예: CMD를 `-Port 8081`로 띄운 상태에서 같은 데이터로 exe를 실행하면 exe는 "같은 데이터 폴더를 쓰는 MentalCare가 이미 실행 중" 창을 띄우고 종료합니다(코드 6). 먼저 떠 있던 쪽은 영향 없음.
- 데이터 폴더가 다르면 (포트만 다르면) 동시에 실행됩니다.
- 잠금은 OS가 프로세스와 함께 회수하므로, 강제 종료·정전 뒤 파일이 남아도 다음 실행을 막지 않습니다. 파일은 비어 있고(개인정보 없음) 지우지 않습니다.
- JVM 안의 세션 잠금(`SessionLocks`)은 그대로이며, 이 잠금은 프로세스 사이만 다룹니다.

### OAuth

- 같은 fat jar, 같은 주소(`127.0.0.1:8080`)이므로 redirect_uri(`http://127.0.0.1:8080/api/auth/chatgpt/callback`)가 같습니다. credential 형식, DPAPI entropy(`mentalcare.chatgpt.credentials.v1`), 저장 위치 규칙(`<data>\credentials`)을 바꾸지 않았습니다.
- 번들 런타임에서도 JNA → DPAPI 복호화가 동작함을 확인했습니다(기존 credential로 `connected=true`, `credentialProvider=windows-dpapi`).

### SmartScreen / 코드 서명

서명하지 않은 exe이므로 다운로드하거나 다른 PC로 옮긴 경우 SmartScreen 경고가 뜰 수 있습니다. 우회 코드는 넣지 않았습니다.

### 여전히 보류

| 항목 | 이유 |
|---|---|
| MSI / installer EXE | WiX Toolset 추가 의존, 설치 위치(`Program Files`, 쓰기 불가)에 맞춘 데이터 기본값(`%LOCALAPPDATA%`)과 업그레이드/제거 시 데이터 보존 정책을 먼저 정해야 함. 1인 로컬 사용에는 portable로 충분 |
| 코드 서명 | 인증서 비용·관리 필요. 직접 빌드해 쓰는 동안은 불필요 |
| 자동 업데이트 | 서명·배포 채널이 전제. 지금은 다시 빌드로 충분 |
| 시작 메뉴 등록 | installer의 일. 필요하면 exe 바로가기를 직접 만들면 됨 |
| 앱 아이콘, 별도 종료 exe(`--add-launcher`) | 기능상 필요 없음(앱 내 종료 버튼, `MentalCare 종료.cmd`) |
