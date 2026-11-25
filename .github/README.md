# BeeUpdate 1.0.0 — Paper 26.3 (Java 25)

> **الحالة:** لم يُبنَ بـ Maven ولم يُجرّب على سيرفر. لا يوجد JAR جاهز. التفاصيل في TEST_REPORT.md.
> المفحوص: توافق الأنواع مع سورس Paper API، ومنطق العملة، ومراجع الـ Resource Pack.

## البناء
**من الهاتف عبر GitHub**
1. أنشئ مستودعًا وارفع محتويات هذا المجلد بحيث يكون pom.xml في الجذر.
2. تأكد من وجود الملف `.github/workflows/build.yml` (مجلد مخفي، قد تحتاج لإنشائه يدويًا بزر Add file).
3. افتح Actions ثم Build BeeUpdate ثم Run workflow.
4. الـ Workflow سيولّد ويفحص Resource Pack أولًا، ثم يبني الإضافة بـ Java 25 ويشغّل اختبارات العملة. نزّل `BeeUpdate.jar` من Artifact باسم `BeeUpdate`. إذا فشل أي فحص، لن يُرفع Artifact.

**على كمبيوتر** (JDK 25 + Maven): `python3 -m pip install pillow pyyaml` ثم `python3 tools/make_pack.py` و`python3 tools/check.py` ثم `mvn clean package` والناتج `target/BeeUpdate.jar`.

## التثبيت
1. ضع BeeUpdate.jar في `plugins/` وشغّل السيرفر (جرّبه على سيرفر اختبار أولًا).
2. ارفع BeeUpdate-ResourcePack.zip على رابط تنزيل مباشر، ثم في `server.properties`:

       resource-pack=<الرابط المباشر>
       resource-pack-sha1=<احسب SHA-1 لملف Resource Pack النهائي بعد تنزيله>
       require-resource-pack=true

   بدون الحزمة ستظهر العناصر والنحل بشكل مفقود. للعمل بدونها اجعل `models.enabled: false` (يؤثر على النحل فقط).

## الأوامر والصلاحيات
| الأمر | الصلاحية | الافتراضي |
|---|---|---|
| /honey | beeupdate.honey | الكل |
| /honey pay <لاعب> <مبلغ> | beeupdate.pay | الكل |
| /bee top و /honey top | beeupdate.top | الكل |
| /bee help | beeupdate.help | الكل |
| /beeshop | beeupdate.shop | الكل |
| /bee give <لاعب> <مبلغ> | beeupdate.admin.give | OP |
| /bee take <لاعب> <مبلغ> | beeupdate.admin.take | OP |
| /bee reload | beeupdate.admin.reload | OP |
| /bee spawn <نوع> [عدد] | beeupdate.admin.spawn | OP |

`beeupdate.admin` تمنح كل صلاحيات الإدارة. النحل المستدعى بالأمر لا يعطي مكافأة (قابل للتغيير).

## الإعدادات (plugins/BeeUpdate/)
- config.yml: معدل الظهور، المناطق المحمية، AFK، حدود المكافآت، الترقيات.
- mobs.yml: المكافأة، HP، الضرر، السرعة، الحجم، الندرة، الوقت، البيومات.
- shop.yml: الأسعار. messages.yml: الرسائل.
- balances.tsv: الأرصدة (ونسخة .bak). لا تعدله والسيرفر يعمل.

`/bee reload` لا يغيّر max-balance ولا save-interval (يحتاجان إعادة تشغيل). خصائص المعدات المشتراة سابقًا تتحدث عند ترقيتها فقط.

## القدرات
| العنصر | القدرة |
|---|---|
| Honey Sword | يبطئ الهدف |
| Warrior Blade | 25% ضرر إضافي +4 |
| Royal Sword | امتصاص 15% من الضرر كصحة |
| Queen's Blade | ضرر واسع على الوحوش القريبة |
| Honey Pickaxe | فرصة Haste |
| Worker Pickaxe | خبرة مضاعفة من الخامات |
| Royal Pickaxe | الغنائم إلى الحقيبة |
| Queen's Pickaxe | 3×3 (انحنِ لإيقافه) |
| درع Worker (قطعتان+) | Haste |
| درع Warrior (قطعتان+) | عكس الضرر |
| درع Royal (قطعتان+) | Regeneration عند ضعف الصحة |
| درع Queen (قطعتان+) | مناعة السم و Absorption |

القدرات للدروع على مستوى الطقم (قطعتان فأكثر). أسلحة المستوى الأول أضعف قليلًا من Netherite، والأعلى أقوى منها.
Special Items: Royal Jelly و Honey Bomb و Bee Repellent.

## حدود يجب أن تعرفها
- Paper لا يسمح بإضافة نوع كائن جديد: الوحش نحلة فانيلا مخفية فوقها ItemDisplay بالموديل. الأجنحة لا ترفرف.
- الظهور "الطبيعي" يتم بنظام ظهور خاص بالإضافة وليس بنظام ماينكرافت.
- إن ظهر الموديل بالعكس اجعل `models.yaw-offset: 180`، وإن كان مرتفعًا أو منخفضًا عدّل `models.y-offset`.
- لا يوجد ربط مباشر مع WorldGuard أو إضافات الـ Claims. أضف المناطق في protected-regions.
- منع النسخ جزئي: يكتشف النسخ المكررة في حقيبة اللاعب نفسه و Ender Chest فقط، لا في الصناديق ولا بين لاعبين، ولا في وضع Creative.
- كشف AFK يعتمد على حركة الكاميرا والدردشة والأوامر، وقد تتجاوزه برامج تحرك الكاميرا.
- تعطيل السيرفر المفاجئ قد يفقد تغييرات آخر ثانيتين تقريبًا.
- التوازن والأداء لم يُقاسا على سيرفر فعلي.

## قائمة اختبار على السيرفر
1. /bee give ثم أعد تشغيل السيرفر وتأكد من بقاء الرصيد.
2. /honey pay بمبلغ سالب وبأكثر من رصيدك.
3. /bee spawn لكل نوع: الشكل والاتجاه والهجوم أكثر من مرة.
4. انتظر الظهور الطبيعي خارج 100 بلوك من Spawn، واقتل وحشًا للتأكد من المكافأة مرة واحدة.
5. اشترِ من كل قسم، وحاول سحب أيقونات المتجر (يجب ألا تتحرك).
6. البس الأطقم وتأكد من الشكل، وجرّب الترقية والقدرات.

## إعادة الاختبار والتوليد
    javac -d out src/main/java/me/beeupdate/{Ledger,RewardGuard}.java tools/LogicTest.java && java -cp out LogicTest
    python3 tools/make_pack.py && python3 tools/check.py
