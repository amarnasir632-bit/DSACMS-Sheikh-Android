# تطبيق إدارة الأسئلة العامة للشيخ

تطبيق Android مستقل للشيخ، مبني بـJava وواجهات Android Views/XML، ومتصل بواجهة الموقع المنشورة على Cloudflare.

## الوضع الحالي

- تسجيل دخول الشيخ يتم عبر Cloudflare Worker في نطاق `www.mohamedalahadi.com`.
- يعرض التطبيق الأسئلة العامة المعلقة والمجاب عنها، ويتيح كتابة الإجابة ونشرها من داخله.
- يستخدم FCM لاستقبال إشعار السؤال الجديد وفتح تفاصيله عند الضغط عليه. حمولة الإشعار لا تتضمن السؤال أو بيانات صاحبه.
- تسجيل رمز الجهاز والنشر يمران عبر جلسة الشيخ المصادق عليها في Cloudflare Worker.

## فتح المشروع

افتح هذا المجلد في Android Studio مع Android SDK API 36 وJDK 17. يعتمد البناء على Android Gradle Plugin 8.13.2 وGradle 8.13. ينتج بناء `assembleDebug` ملف APK في `app/build/outputs/apk/debug/`.

## إعداد الإشعارات على Cloudflare

1. تطبيق Firebase Android بالمعرّف `com.dsacms.sheikhrequests` مضبوط في `app/google-services.json`.
2. أضف Service Account JSON الخاص بـFirebase كـ Worker Secret باسم `FIREBASE_SERVICE_ACCOUNT_JSON` في مشروع Cloudflare Pages. لا تضف المفتاح الخاص إلى التطبيق أو المستودع.
3. انشر Worker ثم نفّذ ترحيل D1 عبر `/api/init-d1` لإضافة جدول أجهزة الإشعارات.
4. ثبّت التطبيق على هاتف الشيخ، اسمح بالإشعارات، وسجّل الدخول بحساب دوره `sheikh`. ستظهر حالة تسجيل الجهاز في صفحة الإعدادات.
5. أرسل سؤالاً تجريبياً من صفحة الأسئلة العامة. ينبغي أن يصل إشعار بعنوان «سؤال عام جديد»، ويفتح تفاصيل السؤال عند الضغط عليه.

نطاق API في التطبيق هو `https://www.mohamedalahadi.com/api` ويجب أن يبقى متوافقاً مع مسارات Worker في `DSACMS-Frontend/_worker.js`.

## البناء

بعد تثبيت Android Studio وSDK المناسب وإضافة ملف Firebase:

```bash
gradle assembleDebug
gradle assembleRelease
```

لإنتاج APK موقّع للنشر، أضف إعداد توقيع محلي آمن ولا تضع keystore أو كلمات مروره في المستودع.
