# 웃긴카메라 (FunnyFaceCamera)

얼굴을 인식해서 실시간으로 우스꽝스럽게 변형해 주는 안드로이드 카메라 앱.
광고 없음, 로그인 없음, 인터넷 권한 없음. 기능은 딱 세 가지:

- 효과 고르기: 왕눈이 / 왕입 / 왕코 / 외계인 / 대두 / 쪼글이
- 📸 찍기: 변형된 화면을 갤러리 `Pictures/FunnyFace` 에 저장
- 🔄 전환: 전면 ↔ 후면 카메라

Android 10 이상에서 동작합니다.

## 폰에 설치하는 방법 A — PC + Android Studio

1. PC에 [Android Studio](https://developer.android.com/studio) 설치
2. 이 zip 파일을 풀고, Android Studio에서 **Open** → `FunnyFaceCamera` 폴더 선택
3. 아래쪽에 Gradle Sync가 끝날 때까지 기다리기 (처음엔 몇 분 걸림)
4. 폰에서 **설정 → 휴대전화 정보 → 소프트웨어 정보 → 빌드번호 7번 탭** → 개발자 옵션 → **USB 디버깅 켜기**
5. 폰을 USB로 PC에 연결하고, 폰에 뜨는 "USB 디버깅 허용" 승인
6. Android Studio 위쪽의 초록색 ▶ (Run) 버튼 누르기 → 폰에 앱이 설치되고 실행됨

## 폰에 설치하는 방법 B — PC에 아무것도 설치하지 않고 (GitHub)

1. GitHub에 새 저장소를 만들고 이 폴더 내용을 전부 업로드 (`.github` 폴더 포함)
2. 저장소의 **Actions** 탭 → **Build APK** 가 자동으로 돌아감 (약 5분)
3. 완료된 실행을 눌러 맨 아래 **Artifacts → funnyface-apk** 다운로드
4. 압축을 풀어 나온 `app-debug.apk` 를 폰으로 옮겨서 설치 ("출처를 알 수 없는 앱" 허용 필요)

## 효과를 바꾸거나 추가하려면

`app/src/main/java/com/funnyface/camera/Warp.kt` 한 파일만 보면 됩니다.

- `bulge(중심x, 중심y, 반지름, 세기)` — 세기가 양수면 그 부위가 커지고, 음수면 작아짐
- 예: 왕눈이를 더 세게 → `Effect.BIG_EYES` 의 `1.3f` 를 `1.8f` 로
