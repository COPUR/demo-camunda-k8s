# ADR-0001: İstisna vakasının kaydı Camunda'dadır

- **Durum:** Kabul edildi
- **Tarih:** 2026-11-02
- **Kapsam:** `app/payment-exception-api` (`adapter.out.camunda`, `port.out.ExceptionCaseRepository`),
  `helm/values-camunda.yaml` (`orchestration.env`)

## Bağlam

P2 `pay-instant-exception` süreci, belirsiz kalan (UNRES) bir FAST ödemesini operasyon ve süpervizör incelemesine
götürür. Vakanın durumu (hangi görev açık, kim karar verdi, SLA doldu mu) zaten motorda vardır: süreç örneği,
kullanıcı görevleri ve değişkenler. İstisna API'si bu vakaları REST ile açar, listeler ve kararını alır.

Önemli nitelikler:

- **Doğruluk:** Tasklist, Operate ve API aynı vakayı aynı durumda göstermeli. Demoda bunu izleyen paydaş üç
  ekranın birbirini tuttuğunu görür.
- **Basitlik:** demo tek komutla kurulur ve başkasına verilir; her yeni kalıcı depo kurulumu, yedeği ve
  şema göçünü getirir.
- **Tutarlılık:** API'nin kendi yazdığını hemen okuması (read-your-writes) yeterli; vakalar arası işlem yok.

## Değerlendirilen seçenekler

1. **API'nin kendi vaka tablosu (PostgreSQL), motor yalnızca iş akışı.** Sorgular hızlı ve esnek. Bedeli: iki kayıt
   sistemi; her karar hem tabloya hem motora yazılır ve ikisi ayrışabilir (bkz. [ADR-0002](0002-no-dual-write.md)).
   Tablo ile motoru eşleyen bir uzlaştırma işi gerekir.
2. **Motorun exporter'ından beslenen okuma modeli (CQRS).** Okuma hızlı, tek yazma noktası motor. Bedeli: exporter,
   tüketici ve depo; demo için fazla parça ve ek gecikme.
3. **Camunda kayıt sistemidir; API motoru okur ve komutlarını ona iletir.** Tek gerçek kaynak, depo yok. Bedeli:
   Camunda arama API'si motoru kısa bir gecikmeyle izler ve bir vaka birkaç aramayla okunur.

## Karar

Seçenek 3. Vaka Camunda'dadır; API'nin veritabanı yoktur. Camunda kavramları (süreç örneği, kullanıcı görevi,
değişken) yalnızca `adapter.out.camunda` içinde kalır, alan modeli `ExceptionCase`, `ReviewTask` ve `CaseState`
ile konuşur (anti-corruption layer). Ödeme kimliği süreç örneğinin `businessId`'sidir ve motorda tekillik açıktır
(`CAMUNDA_PROCESSINSTANCECREATION_BUSINESSIDUNIQUENESSENABLED=true`): aynı ödeme için ikinci örnek `409` alır, API
bunu `PEX-3002` yapar.

## Sonuçlar

**Kazandıklarımız**

- Tasklist, Operate ve API aynı kaydı gösterir; uzlaştırma işi yoktur.
- Kopyalar arası eşzamanlılık çiti motordadır (`businessId` tekilliği, görev durumu), API'de dağıtık kilit gerekmez.
- Kurulacak, yedeklenecek bir veritabanı yoktur.

**Bedeli**

- **Okuma gecikmesi.** Arama API'si motoru geriden izler. API, kendi açtığı vakaları iki dakika hatırlar
  (read-your-writes); başka bir kopyadan okuyan istemci tekrar dener.
- **Okuma sırası.** Bir vaka birkaç aramayla okunur ve her arama motoru başka bir anda yakalar. Kural: sonradan
  yazılanı önce oku (açık görev, sonra operasyon görevinin bitişi, en son değişkenler). İlk sürüm değişkenleri
  önce okuyordu ve yükseltilen bir vakayı bir an "operasyon SLA'sı doldu" diye gösteriyordu; gerçek motor testi
  bunu yakaladı ve kural bu yüzden var.
- Sorgular motorun arama yeteneğiyle sınırlıdır; raporlama gibi karmaşık sorgular ayrı bir okuma modeli ister.
- Tekillik Camunda 8.10'da varsayılan olarak kapalıdır; motor ayarı bu kararın parçasıdır ve kurulumla gelir.

## Denetim

- `ArchitectureTest` (ArchUnit): Camunda istemcisi yalnızca Camunda adaptöründe; alan katmanı yalnızca `java.*`.
- `app/payment-exception-api/e2e/run.sh`: gerçek Camunda 8.10.4'te vaka açma, ikinci örneğe `409`, yükseltilen
  vakanın nedeninin süpervizör görevi göründüğü anda doğru olması.
- `k8s/65` `demo-api-check` Job'u: `./demo.sh up` sonunda API'yi gerçek token'larla dener.

## Ne zaman yeniden bakılır

Vaka üzerinde motorun arayamadığı sorgular (raporlama, çok alanlı filtre) gerekirse ya da okuma gecikmesi paydaşın
gözünde sorun olursa: o zaman Seçenek 2 (exporter'dan okuma modeli) açılır, yazma noktası yine motor kalır.
