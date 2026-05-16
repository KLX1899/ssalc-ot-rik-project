
# AUT LMS Auto Joiner

A Python automation tool built with Playwright and APScheduler that automatically logs into Amirkabir University of Technology's online class platforms, waits for your scheduled classes to start, joins the sessions as "listen-only", and keeps the browser open until the class is over.

## Supported Platforms

| Platform | URL | Session File |
|----------|-----|-------------|
| **LMS** (BigBlueButton) | `lmshome.aut.ac.ir` | `state.json` |
| **NIMA** (نیما) | `lms.aut.ac.ir` | `state_nima.json` |

> **How to tell them apart:** If your class link lives on **lmshome.aut.ac.ir** (the Moodle-based BBB portal), it is **LMS**. If the professor set it up through the Angular-based **NIMA** panel at `lms.aut.ac.ir`, it is **NIMA**. The two NIMA classes for CS students are typically **انقلاب اسلامي ايران** and **آزمايشگاه شبکه‌هاي کامپيوتري**; everything else is LMS.

## Features

* **Dual-Platform Support**: Handles both LMS (BigBlueButton) and NIMA (نیما) class platforms with a single scheduler.
* **Fully Automatic Login**: On first run (or when a session expires), the tool logs in automatically using your `.env` credentials — no need to run any login script manually.
* **Session Recovery**: If a session expires mid-run (even during the poll loop), the tool detects the redirect to the login page, re-authenticates, and continues without missing the class.
* **Smart Polling**: If the class has not started yet ("هنوز برگزار نشده"), the tool waits and refreshes the page every **1 minute** automatically.
* **Auto-Join**: Automatically clicks the required buttons to join the BigBlueButton audio in listen-only mode (LMS) or enters the NIMA classroom.
* **Persistent Browser**: After joining, the browser **stays open** for the full duration of the class (end time + 5-minute buffer) so you can interact with it manually if needed.
* **Catch-up Mechanism**: If you start the script while a class is already in progress, it will join immediately.
* **YAML Scheduling**: Easily manage your weekly class schedule using a simple configuration file with a `platform` field.
* **Timezone Aware**: Fully configured for Iran Standard Time (`Asia/Tehran`).

## Prerequisites

* Python 3.8 or higher
* Pip (Python package manager)

## Installation

1. Clone or download this repository.
2. Install the required Python packages:
   ```bash
   pip install -r requirements.txt
   ```
3. Install the Playwright Chromium browser:
   ```bash
   playwright install chromium
   ```

## Configuration

### 1. Credentials (`.env`)

Create a `.env` file in the root directory of the project with your AUT portal credentials:

```env
LMS_USERNAME=your_student_id
LMS_PASSWORD=your_password
```

> The same credentials are used for both LMS and NIMA since both use the university's CAS SSO system.

### 2. Schedule (`schedule.yaml`)

Create or edit the `schedule.yaml` file. Each class entry requires a `platform` field:

```yaml
classes:
  # ── NIMA classes ──────────────────────────────────────
  - name: "انقلاب اسلامي ايران"
    days: ["sat"]
    start: "10:00"
    end: "12:00"
    platform: "NIMA"

  - name: "آزمايشگاه شبکه‌هاي کامپيوتري"
    days: ["sat"]
    start: "08:00"
    end: "10:00"
    platform: "NIMA"

  # ── LMS (BigBlueButton) classes ───────────────────────
  - name: "شبکه‌هاي کامپيوتري"
    days: ["sat", "mon"]
    start: "15:00"
    end: "16:30"
    platform: "LMS"

  - name: "روش پژوهش و ارائه"
    days: ["mon"]
    start: "16:30"
    end: "18:00"
    platform: "LMS"
```

| Field | Required | Description |
|-------|----------|-------------|
| `name` | ✅ | Course name as it appears on the platform (LMS dashboard link text, or a distinctive prefix of the NIMA course title) |
| `days` | ✅ | List of weekdays: `sun`, `mon`, `tue`, `wed`, `thu`, `sat`, `fri` |
| `start` | ✅ | Class start time in 24-hour format (`HH:MM`) |
| `end` | ✅ | Class end time in 24-hour format (`HH:MM`). The browser stays open until this time + 5 min |
| `platform` | ✅ | `"LMS"` or `"NIMA"`. Defaults to `"LMS"` if omitted |

> **NIMA name matching note:** NIMA renders full titles like `"انقلاب اسلامی ایران - گروه 01 - میرخلیلی"`. The tool matches rows primarily by **time range** (e.g., `"10:00 - 12:00"`) and uses only the first 6 characters of `name` as a secondary check, so you do not need to type the full title.

## Usage

Just run the scheduler. That's it.

```bash
python scheduler.py
```

**On first run**, the scheduler checks whether session files (`state.json` / `state_nima.json`) exist for the platforms used in your schedule. If any are missing, it opens a browser window and logs in automatically before starting. Leave the terminal open — the scheduler runs in the background and joins classes at the right times.

> You can still run the login scripts manually if you ever need to refresh a session on demand:
> ```bash
> python login_setup.py        # refresh LMS session  (→ state.json)
> python login_setup_nima.py   # refresh NIMA session (→ state_nima.json)
> ```

### How It Works

```
python scheduler.py
       │
       ├─ 1. STARTUP LOGIN CHECK
       │      Is state.json missing?      → run LMS login  automatically
       │      Is state_nima.json missing? → run NIMA login automatically
       │
       ├─ 2. REGISTER JOBS
       │      CronTrigger for each class at its scheduled day/time
       │      DateTrigger  for any class already in progress (catch-up)
       │
       └─ 3. WAIT — for each trigger fire:
                    │
                    ▼
             join_class(spec)
                    │
                    ├─ Open browser with saved session
                    │
                    ├─ Session expired? ──► re-login automatically ──► retry
                    │
                    ├─ Navigate to course
                    │    ┌──────────────────┬─────────────────────┐
                    │    │ LMS              │ NIMA                │
                    │    │ lmshome →        │ lms.aut.ac.ir →     │
                    │    │ course page      │ ongoing meetings    │
                    │    └──────────────────┴─────────────────────┘
                    │
                    ├─ Poll every 1 min until join button appears
                    │
                    ├─ Click Join → BBB opens → select listen-only audio
                    │
                    └─ Stay open until end_time + 5 min buffer
```

### Session Expiry Recovery

Session expiry is handled automatically at **three points**:

| When | What happens |
|------|-------------|
| **Startup** | State file missing → login runs before the scheduler starts |
| **On navigation** | Browser redirects to login page after `page.goto()` → closes browser, re-logs in, reopens with fresh cookies |
| **Mid-poll** | URL changes to login page during the refresh loop → same recovery as above, then resumes polling |

## File Structure

```
.
├── scheduler.py           # APScheduler-based cron scheduler + startup login check
├── joiner.py              # Core logic: navigate, poll, join BBB, keep-alive
├── login_setup.py         # LMS login flow  (callable as do_login_lms())
├── login_setup_nima.py    # NIMA login flow (callable as do_login_nima())
├── schedule.yaml          # Your weekly class timetable
├── state.json             # (auto-generated) LMS session cookies
├── state_nima.json        # (auto-generated) NIMA session cookies
├── .env                   # Your credentials — DO NOT commit this file
├── requirements.txt       # Python dependencies
└── README.md
```

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Browser opens at startup asking for login | Normal on first run or after session expiry — just wait for it to finish |
| `LMS_USERNAME and LMS_PASSWORD must be set` | Create a `.env` file with your credentials as shown above |
| `Could not log in automatically` | Credentials in `.env` may be wrong, or the university CAS page changed — verify by running `python login_setup.py` manually |
| Button not found / stuck refreshing | The class may be on the wrong platform. Double-check the `platform` field in `schedule.yaml` |
| NIMA: row matched but name not found | The `name` in your YAML must share at least its first 6 characters with what NIMA displays |
| Audio modal not appearing | BBB may be loading slowly — the script waits up to 60 seconds. On failure a screenshot is saved as `error_<coursename>.png` |
| Browser closes too early | Ensure `end` is set correctly in `schedule.yaml`. The browser stays open until 5 minutes after `end` |
| Class joined but no audio | The listen-only button selector may have changed. Check `error_<coursename>.png` for a screenshot of where it got stuck |

---

# ورود خودکار به کلاس‌های آنلاین امیرکبیر

یک ابزار اتوماسیون پایتون با استفاده از Playwright و APScheduler که به صورت خودکار وارد سامانه‌های آنلاین دانشگاه صنعتی امیرکبیر می‌شود، منتظر زمان شروع کلاس می‌ماند، به عنوان «فقط شنونده» وارد کلاس می‌شود و مرورگر را تا پایان کلاس باز نگه می‌دارد.

## پلتفرم‌های پشتیبانی شده

| پلتفرم | آدرس | فایل نشست |
|---------|------|-----------|
| **LMS** (بیگ‌بلو‌باتن) | `lmshome.aut.ac.ir` | `state.json` |
| **NIMA** (نیما) | `lms.aut.ac.ir` | `state_nima.json` |

> **تشخیص پلتفرم:** اگر لینک کلاس شما در **lmshome.aut.ac.ir** (پورتال BBB مبتنی بر Moodle) باشد، پلتفرم **LMS** است. اگر استاد آن را از طریق پنل Angular مبتنی بر **نیما** در `lms.aut.ac.ir` تنظیم کرده باشد، پلتفرم **NIMA** است. معمولاً دروس **انقلاب اسلامي ايران** و **آزمايشگاه شبکه‌هاي کامپيوتري** روی نیما هستند و بقیه روی LMS.

## ویژگی‌ها

* **پشتیبانی دو پلتفرمی**: مدیریت همزمان کلاس‌های LMS (بیگ‌بلو‌باتن) و NIMA (نیما) با یک زمان‌بند واحد.
* **ورود کاملاً خودکار**: در اولین اجرا (یا پس از انقضای نشست)، ابزار به صورت خودکار با اطلاعات موجود در `.env` وارد می‌شود — بدون نیاز به اجرای دستی هیچ اسکریپتی.
* **بازیابی نشست**: اگر نشست در حین اجرا منقضی شود (حتی در حلقه بررسی)، ابزار ریدایرکت به صفحه ورود را تشخیص داده، مجدداً احراز هویت کرده و بدون از دست دادن کلاس ادامه می‌دهد.
* **بررسی هوشمند وضعیت**: در صورتی که کلاس هنوز شروع نشده باشد ("هنوز برگزار نشده")، هر **۱ دقیقه** صفحه را رفرش می‌کند.
* **ورود خودکار**: کلیک خودکار دکمه‌های لازم برای ورود به BBB (حالت فقط شنونده) یا ورود به کلاس نیما.
* **مرورگر پایدار**: پس از ورود، مرورگر تا **پایان کلاس + ۵ دقیقه بافر** باز می‌ماند.
* **قابلیت جبران (Catch-up)**: اگر اسکریپت را در حالی اجرا کنید که زمان کلاس از قبل شروع شده باشد، بلافاصله وارد آن کلاس می‌شود.
* **زمان‌بندی با YAML**: مدیریت آسان برنامه کلاسی هفتگی با فیلد `platform`.
* **پشتیبانی از منطقه زمانی**: تنظیم شده برای منطقه زمانی ایران (`Asia/Tehran`).

## پیش‌نیازها

* پایتون نسخه ۳.۸ یا بالاتر
* مدیریت بسته‌های پایتون (Pip)

## نصب

۱. این مخزن را دانلود یا Clone کنید.
۲. نیازمندی‌های پایتون را نصب کنید:
   ```bash
   pip install -r requirements.txt
   ```
۳. مرورگر کرومیوم مربوط به Playwright را نصب کنید:
   ```bash
   playwright install chromium
   ```

## تنظیمات

### ۱. اطلاعات ورود (`.env`)

یک فایل `.env` در مسیر اصلی پروژه ایجاد کنید:

```env
LMS_USERNAME=شماره_دانشجویی
LMS_PASSWORD=رمز_عبور
```

> از همان اطلاعات برای هر دو پلتفرم استفاده می‌شود زیرا هر دو از سامانه CAS دانشگاه استفاده می‌کنند.

### ۲. برنامه کلاسی (`schedule.yaml`)

```yaml
classes:
  # ── کلاس‌های NIMA ──────────────────────────────────
  - name: "انقلاب اسلامي ايران"
    days: ["sat"]
    start: "10:00"
    end: "12:00"
    platform: "NIMA"

  - name: "آزمايشگاه شبکه‌هاي کامپيوتري"
    days: ["sat"]
    start: "08:00"
    end: "10:00"
    platform: "NIMA"

  # ── کلاس‌های LMS (بیگ‌بلو‌باتن) ───────────────────
  - name: "شبکه‌هاي کامپيوتري"
    days: ["sat", "mon"]
    start: "15:00"
    end: "16:30"
    platform: "LMS"

  - name: "روش پژوهش و ارائه"
    days: ["mon"]
    start: "16:30"
    end: "18:00"
    platform: "LMS"
```

| فیلد | ضروری | توضیحات |
|------|-------|---------|
| `name` | ✅ | نام درس مطابق با پلتفرم (متن لینک داشبورد LMS یا پیشوند متمایز عنوان نیما) |
| `days` | ✅ | روزهای هفته: `sun`، `mon`، `tue`، `wed`، `thu`، `sat`، `fri` |
| `start` | ✅ | ساعت شروع به فرمت ۲۴ ساعته (`HH:MM`) |
| `end` | ✅ | ساعت پایان به فرمت ۲۴ ساعته (`HH:MM`). مرورگر تا این ساعت + ۵ دقیقه باز می‌ماند |
| `platform` | ✅ | `"LMS"` یا `"NIMA"`. در صورت خالی بودن، پیش‌فرض `LMS` است |

> **نکته تطبیق نام در نیما:** نیما عنوان کامل مثل `"انقلاب اسلامی ایران - گروه 01 - میرخلیلی"` را نمایش می‌دهد. ابزار ابتدا با **بازه زمانی** (مثلاً `"10:00 - 12:00"`) تطبیق می‌دهد و فقط ۶ حرف اول `name` را به عنوان بررسی تکمیلی استفاده می‌کند.

## نحوه استفاده

فقط زمان‌بند را اجرا کنید. همین.

```bash
python scheduler.py
```

**در اولین اجرا**، زمان‌بند بررسی می‌کند که آیا فایل‌های نشست (`state.json` / `state_nima.json`) برای پلتفرم‌های مورد استفاده وجود دارند یا نه. اگر هر کدام نباشند، به صورت خودکار مرورگر را باز کرده و وارد می‌شود. این ترمینال را باز بگذارید.

> در صورت نیاز می‌توانید اسکریپت‌های ورود را به صورت دستی هم اجرا کنید:
> ```bash
> python login_setup.py        # بازنشانی نشست LMS  (→ state.json)
> python login_setup_nima.py   # بازنشانی نشست NIMA (→ state_nima.json)
> ```

### نحوه کار

```
python scheduler.py
       │
       ├─ ۱. بررسی ورود در شروع
       │      state.json وجود ندارد؟      → ورود خودکار به LMS
       │      state_nima.json وجود ندارد؟ → ورود خودکار به NIMA
       │
       ├─ ۲. ثبت زمان‌بندی‌ها
       │      CronTrigger برای هر کلاس در روز و ساعت مشخص
       │      DateTrigger  برای کلاس‌هایی که از قبل شروع شده‌اند
       │
       └─ ۳. انتظار — به ازای هر trigger:
                    │
                    ▼
             join_class(spec)
                    │
                    ├─ باز کردن مرورگر با نشست ذخیره شده
                    │
                    ├─ نشست منقضی شده؟ ──► ورود مجدد خودکار ──► ادامه
                    │
                    ├─ رفتن به صفحه کلاس
                    │    ┌──────────────────┬─────────────────────┐
                    │    │ LMS              │ NIMA                │
                    │    │ lmshome →        │ lms.aut.ac.ir →     │
                    │    │ صفحه درس         │ جلسات فعال          │
                    │    └──────────────────┴─────────────────────┘
                    │
                    ├─ بررسی هر ۱ دقیقه تا ظاهر شدن دکمه ورود
                    │
                    ├─ کلیک ورود → باز شدن BBB → انتخاب فقط شنونده
                    │
                    └─ باز ماندن تا end_time + ۵ دقیقه بافر
```

