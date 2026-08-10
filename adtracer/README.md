# AdTracer — SDK theo dõi vòng đời quảng cáo (debug-only)

SDK độc lập (0 dependency, **không đụng vào `:ads` / `:onboardkitorigin`**) ghi lại ads đi đâu về đâu: request → loaded → shown / bỏ phí / bỏ lỡ, show rate theo từng placement.

Module này là **nguồn dữ liệu**: thu sự kiện, ghi journal, và phục vụ dashboard qua HTTP + SSE trên loopback.

> **Giao diện dashboard KHÔNG được sửa ở đây.** Source của nó nằm ở project riêng
> `CLUA/TestAds`; file `src/main/assets/adtracer/index.html` là **artifact sinh ra**
> bởi `npm run ship` bên đó. Sửa trực tiếp file này sẽ bị ghi đè ở lần ship kế tiếp.

## Cách dùng

1. Build & cài bản **debug** lên device (release hoàn toàn không chứa tracker).
2. Nối cổng rồi mở trình duyệt:

```bash
adb forward tcp:8686 tcp:8686
```

3. Mở **http://localhost:8686**.

Cổng 8686 bận thì SDK tự thử 8687–8695 — xem logcat để biết cổng thật:

```bash
adb logcat -s AdTracer
```

Muốn sửa/nâng cấp giao diện thì làm ở `CLUA/TestAds` (có dev server, mock data, test, và `npm run ship` để đẩy bản build vào đây).

## API mà module này phục vụ

| Endpoint | Trả về |
|---|---|
| `GET /` | dashboard (một file HTML tự chứa, lấy từ assets) |
| `GET /events?after=N` | SSE, frame `id: <sessionId>:<seq>` + `data: <json>`, heartbeat `: ping` mỗi 15s |
| `GET /api/sessions` | `[{file, sizeBytes, modifiedMs, current}]`, mới nhất trước |
| `GET /api/session/<file>` | NDJSON thô của một session |

`Last-Event-ID` được ưu tiên hơn `?after=`, và chỉ có tác dụng khi phần sessionId khớp phiên hiện tại — nhờ vậy trình duyệt reconnect sau khi app restart không bị bỏ sót sự kiện đầu phiên mới.

## Phạm vi đo (quan trọng — đọc để hiểu số liệu)

Tracker bám ở **tầng app** (callback của `AdsManager`, decorator quanh `OnboardingAdProvider`, callback công khai của `AppOpenManager`), vì vậy:

| Nhóm | Đo được | Ghi chú |
|---|---|---|
| Native app-side (permission, home, survey, confirm_uninstall, welcome, preview_*) | request, loaded, fail, skip + lý do, **shown = lúc render thật**, re-render, click | chính xác theo từng instance ad |
| Interstitial (inter_onboarding, inter_welcome) | request, loaded (đã lọc "phantom null" khi purchased/click-cap), fail, show attempt, **shown = đóng ad xong**, blocked + lý do, show-fail (tách synthetic), click, auto-reload fill | |
| OnboardKit (splash_inter, language1/2, step_*, fullscreen_*, ob5, question_*, splash_banner) | request, loaded/fail (qua polling — badge `≈`, đúng kết cục nhưng latency xấp xỉ), **shown native = bindNative thành công** (né bug double-fire OB3), shown inter = onFinished từ onAdClosed, discarded khi releaseAll/releaseNative | không sửa module onboardkit |
| Banner (banner_home) | request, loaded, fail, **impression** (dùng làm "hiển thị"), refresh của GMA, click | banner thường: impression thật; collapsible: impression suy từ loaded (badge `≈`) vì SDK không forward |
| Rewarded (reward_example) | request, loaded, fail, shown, earned, show-fail, click | |
| App-open resume (open_resume) | **chỉ đếm lượt hiển thị** + impression/dismiss/click/show-fail | SDK không lộ sự kiện load ra ngoài → không có mẫu số |
| Welcome-resume rule | mỗi lần app foreground: lý do chặn hoặc `welcome_launched` | |

Không đo được ở tầng này (cần sửa `:ads` — xem `ADTRACER_PLAN.md` ở gốc project nếu muốn nâng cấp): impression GMA của native/interstitial, paid event (doanh thu), click của app-open splash flows.

## Đảm bảo số liệu

- Sự kiện đánh số thứ tự liền mạch theo session `(sessionId, seq)` — client dedupe nên reconnect/replay không bao giờ đếm trùng.
- Journal ghi đĩa trước khi đẩy và **là nguồn replay** — kill app không mất lịch sử (giữ 10 session gần nhất).
- Fill "ma" bị loại: khi user đã mua hoặc dính cap click, SDK trả wrapper rỗng — tracker kiểm tra `isReady` nên không tính là loaded.
- Load skip / show blocked luôn kèm lý do máy đọc được; "không có sự kiện" luôn nghĩa là "không có gì xảy ra", không phải "xảy ra âm thầm".
- Bản release: module không được đóng gói (`debugImplementation`), mọi call site còn lại là hàm no-op rỗng bị R8 loại bỏ.
