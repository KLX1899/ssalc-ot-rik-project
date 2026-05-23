## ⚠️ Precaution

<span style="color:orange">

> Any misuse or unauthorized activity related to this repository is the sole responsibility of the user. The authors and contributors disclaim any liability for damages or legal consequences arising from improper use, as the usage of this application is permitted **only while actively listening to the class**. Please read the Terms of Service below.

</span>

# AUT LMS Auto Joiner (Android App)

An Android automation application built with Kotlin (and python for desktop users) and a background WebView that automatically logs into AUT's online class platforms, waits for your scheduled classes to start, joins the sessions as "listen-only", and keeps the session active until the class is over. 

## Supported Platforms

| Platform | URL | Authentication |
|----------|-----|-------------|
| **LMS** (BigBlueButton) | `lmshome.aut.ac.ir` | AUT CAS SSO |
| **NIMA** (نیما) | `lms.aut.ac.ir` | AUT CAS SSO |

> **How to tell them apart:** If your class link lives on **lmshome.aut.ac.ir** (the Moodle-based BBB portal), it is **LMS**. If the professor set it up through **NIMA** panel at `lms.aut.ac.ir`, it is **NIMA**. you should set these in 'مدیریت کلاس' section. 

## Features

* **Fully Native Android App**: No more command line or Python dependencies. Runs directly on your phone using an invisible background WebView.
* **Auto Class Discovery**: Automatically scrapes your dashboard and course pages to find your schedule, days, and times. 
* **Class Management UI**: Easily add, edit, toggle, or delete classes through a built-in graphical interface.
* **Dual-Platform Support**: Seamlessly handles both LMS (BigBlueButton) and NIMA (نیما) class platforms.
* **Fully Automatic Login**: Logs in automatically using your saved credentials and securely locks them behind device Biometrics (Fingerprint/Face ID).
* **Smart Polling & Auto-Join**: If the class has not started yet, the app waits and refreshes the page automatically, clicking join buttons as soon as they appear.
* **Foreground Service & Wakelocks**: Runs reliably in the background using Android's Foreground Services.

## Installation

1. Download the latest `.apk` release from the repository.
2. Install the app on your Android device.
3. Grant the **"Display over other apps"** (Overlay) permission when prompted.

## Usage

1. **Enter Credentials**: Enter your AUT Portal Student ID and Password.
2. **Discover Classes**: Tap **"Discover Classes"**. The app will scan your portal and populate your schedule.
3. **Manage Classes**: Verify the schedule in the Manage section. Ensure the platform (LMS or NIMA) is correctly assigned.
4. **Automation**: The service will now run in the background. You can close the UI; the notification drawer will show current activity.

## 🐛 Reporting Bugs & Feedback

If you encounter any issues, please help improve the tool by reporting them:

1. **Check the Logs**: Use the built-in log viewer on the main screen of the app.
2. **Open an Issue**: Go to the [Issues](../../issues) tab of this repository.
3. **Provide Details**: When reporting, please include:
    * Your Android version.
    * A copy-paste of the relevant logs from the app's log viewer.
    * Which platform the bug occurred on (LMS or NIMA).
    * A screenshot if the issue is in the User Interface.

---

## 📜 Terms of Service

By downloading, installing, or using the AUT LMS Auto Joiner application, you agree to the following terms:

1. **Intended Use:** This application is designed solely as an assistive tool to join online classes **while the user is actively present and listening**. 
2. **Prohibited Conduct:** You agree NOT to use this application to artificially inflate attendance or skip classes.
3. **Disclaimer of Liability:** The authors are NOT responsible for any missed classes or academic penalties. The software is provided "AS IS".
4. **Data Privacy:** Credentials are saved entirely locally on your device and are never transmitted to any third-party server.

---
<br>

# ورود خودکار به کلاس‌های آنلاین امیرکبیر (اپلیکیشن اندروید)

یک اپلیکیشن اتوماسیون اندروید (توسعه‌یافته با کاتلین) که به صورت خودکار وارد سامانه‌های آنلاین می‌شود و نشست را تا پایان کلاس فعال نگه می‌دارد.

## پلتفرم‌های پشتیبانی شده

| پلتفرم | آدرس | سیستم ورود |
|---------|------|-----------|
| **LMS** (بیگ‌بلو‌باتن) | `lmshome.aut.ac.ir` | AUT CAS SSO |
| **NIMA** (نیما) | `lms.aut.ac.ir` | AUT CAS SSO |

## ویژگی‌ها

* **اپلیکیشن بومی اندروید**: اجرای مستقیم روی گوشی بدون نیاز به سیستم جانبی.
* **یافتن خودکار کلاس‌ها (Discovery)**: استخراج خودکار زمان‌بندی کلاس‌ها از پورتال دانشجویی.
* **رابط کاربری مدیریت**: امکان ویرایش دستی زمان کلاس‌ها و نوع پلتفرم (LMS/NIMA).
* **امنیت بیومتریک**: محافظت از اطلاعات ورود با اثر انگشت یا تشخیص چهره.
* **اجرای در پس‌زمینه**: استفاده از سرویس‌های اندروید برای اجرای پایدار حتی زمان بسته بودن برنامه.

## نحوه استفاده

۱. **اطلاعات ورود**: شماره دانشجویی و رمز عبور خود را وارد کنید.
۲. **جستجوی کلاس‌ها**: با زدن دکمه **"جستجوی کلاس‌ها"**، برنامه لیست دروس شما را استخراج می‌کند.
۳. **مدیریت دروس**: در بخش مدیریت، کلاس‌ها را فعال کرده و از صحت ساعت برگزاری اطمینان حاصل کنید.
۴. **اتوماسیون**: برنامه در ساعت مقرر به صورت خودکار عملیات ورود را انجام می‌دهد.

## 🐛 گزارش خطا و بازخورد

در صورت بروز هرگونه مشکل، از طریق مراحل زیر به ما اطلاع دهید:

۱. **بررسی لاگ‌ها**: از بخش نمایش لاگ (Logs) در صفحه اصلی برنامه استفاده کنید.
۲. **ثبت Issue**: یک گزارش جدید در قسمت [Issues](../../issues) این مخزن باز کنید.
۳. **اطلاعات مورد نیاز**: لطفاً نسخه اندروید، متن لاگ‌های مربوطه و نام پلتفرمی که در آن مشکل داشتید را در گزارش خود ذکر کنید.

---

## 📜 شرایط و قوانین استفاده (Terms of Service)

با نصب و استفاده از این برنامه، شما با شرایط زیر موافقت می‌کنید:

۱. **هدف استفاده:** این اپلیکیشن صرفاً جهت تسهیل ورود به کلاس **در زمان حضور فعال دانشجو** طراحی شده است.
۲. **موارد ممنوعه:** هرگونه استفاده جهت دور زدن حضور و غیاب یا تقلب ممنوع بوده و مسئولیت آن بر عهده کاربر است.
۳. **سلب مسئولیت:** توسعه‌دهندگان هیچ مسئولیتی در قبال غیبت‌های احتمالی یا عواقب آموزشی ناشی از خطای نرم‌افزاری ندارند.
۴. **حریم خصوصی:** رمز عبور شما فقط روی حافظه گوشی خودتان ذخیره شده و به هیچ سروری ارسال نمی‌شود.