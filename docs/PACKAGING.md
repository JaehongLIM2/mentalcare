# PACKAGING.md — Windows 실행 방식과 jpackage 검토

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

## jpackage 기반 `MentalCare.exe` 검토

### 무엇이 가능한가
JDK 21에 포함된 `jpackage`로 다음 두 형태를 만들 수 있습니다.
- **app-image**: `MentalCare\MentalCare.exe` + 번들된 런타임(`runtime\`) 폴더. 추가 도구 불필요.
- **msi/exe 설치 프로그램**: 시작 메뉴 바로가기, 설치/제거. **WiX Toolset 3.x**가 추가로 필요.

실행 파일은 콘솔 없이(`--win-console` 미지정) 실행되므로 javaw와 같은 효과가 있고, JDK를 따로 설치할 필요가 없습니다. Spring Boot fat jar는 `--input backend/target --main-jar mentalcare.jar`로 그대로 넣을 수 있습니다(모듈화 불필요).

```
jpackage --type app-image --name MentalCare --app-version 0.1.0 ^
  --input backend\target --main-jar mentalcare.jar ^
  --icon packaging\mentalcare.ico --dest dist ^
  --java-options "-Dmentalcare.launcher=jpackage"
```

### 지금 추가하려면 먼저 바꿔야 하는 것
1. **데이터 경로 기본값**: 현재 기본은 실행 위치 기준 `../data`입니다. 설치형 앱은 `Program Files` 아래에서 실행되어 쓰기가 불가능하므로 기본값을 `%LOCALAPPDATA%\MentalCare\data`로 바꾸고 환경변수가 없을 때 그 경로를 쓰도록 `DataDirectoryConfig`를 수정해야 합니다. Docker/스크립트 모드와의 호환을 위해 "런처 모드"를 시스템 속성으로 구분합니다.
2. **중복 실행·브라우저 열기·종료를 Java 안으로 이동**: PowerShell이 없으니 애플리케이션 자체가 (a) 8080이 이미 `/api/health` UP이면 브라우저만 열고 종료, (b) 준비되면 `Desktop.browse()`로 브라우저 열기, (c) 트레이 아이콘 또는 `MentalCare 종료` 두 번째 런처(`--stop` 인자로 `/api/system/shutdown` 호출)를 제공해야 합니다. `jpackage --add-launcher`로 두 번째 exe를 만들 수 있습니다.
3. **로그 위치**: `%LOCALAPPDATA%\MentalCare\logs`로 고정.
4. **OAuth redirect_uri**: 변함없이 `http://127.0.0.1:8080/...`이므로 영향 없음. 단, 포트 충돌 시 안내 UI가 필요합니다.
5. **서명**: 코드 서명이 없으면 SmartScreen 경고가 뜹니다. 개인용이면 "추가 정보 → 실행"으로 넘길 수 있지만 매 버전 반복됩니다.
6. **업데이트**: 재설치 방식. 데이터는 `%LOCALAPPDATA%`에 있어 보존됩니다(설치 폴더와 분리).
7. **크기**: 번들 런타임 포함 약 120~150 MB. `jlink`로 줄일 수 있으나 Spring Boot fat jar에 필요한 모듈 목록(java.sql, java.naming, java.desktop, java.management, jdk.crypto.ec 등)을 검증해야 합니다.
8. **Maven 통합**: `jpackage`는 별도 단계이므로 `scripts\package-exe.cmd`(또는 Maven profile + exec plugin)로 호출합니다.

### 현재 단계 권고: 보류
- 지금 목표(터미널 없이 매주 실행)는 javaw 스크립트로 충족됩니다.
- jpackage는 위 1~2번의 코드 변경(경로 기본값, 자체 중복 실행/브라우저/종료 로직)을 동반하며, 그 변경은 Docker와 스크립트 모드에도 영향을 줍니다. 실제 사용 몇 주 후 "JDK 폴더 의존이 불편하다", "시작 메뉴/자동 시작이 필요하다"가 확인되면 그때 Phase 2 항목으로 추가하는 것이 적절합니다.
- 추가할 때의 순서: ① `%LOCALAPPDATA%` 기본 경로 + 런처 모드 → ② Java 내 단일 인스턴스/브라우저/`--stop` 런처 → ③ `jpackage --type app-image` → ④ (선택) WiX로 msi.
