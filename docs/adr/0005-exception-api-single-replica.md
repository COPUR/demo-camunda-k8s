# ADR-0005: İstisna API'si tek kopya ve `Recreate`

- **Durum:** Kabul edildi
- **Tarih:** 2026-11-02
- **Kapsam:** `k8s/45-payment-exception-api.yaml`, `app/payment-exception-api`
  (`adapter.out.idempotency`, `adapter.out.events`, okuma önbelleği, hız sınırı)

## Bağlam

API'nin birkaç durumu bellektedir:

- **Idempotency kayıtları:** (işlem, çağıran, `Idempotency-Key`) için ilk yanıt; tekrar gelen istek aynı yanıtı
  alır (`Idempotent-Replayed: true`), farklı istek `422 PEX-3006`, süren istek `409 PEX-3007`.
- **Outbox:** teslim bekleyen olaylar ([ADR-0002](0002-no-dual-write.md)).
- **Read-your-writes önbelleği:** bu kopyanın iki dakika içinde açtığı vakalar ([ADR-0001](0001-camunda-system-of-record.md)).
- **Hız sınırı** ve aynı ödeme için kopya içi kilit.

İkinci bir kopya bu durumu paylaşmaz: tekrar gelen bir istek diğer kopyaya düşerse yeni istek sayılır. Kubernetes
`RollingUpdate` ile bir dağıtım sırasında eski ve yeni pod kısa süre yan yana çalışır; bu da geçici bir ikinci
kopyadır.

Önemli nitelikler: demoda doğruluk, kurulum basitliği. Yüksek erişilebilirlik demo için hedef değildir
(üretim topolojisi değildir).

## Değerlendirilen seçenekler

1. **İki kopya, bellekte durum.** Bedeli: idempotency garantisi kopya başına; tekrar deneme yanlış kopyaya
   düşerse ikinci komut olarak çalışır.
2. **İki kopya, durum PostgreSQL'de.** Doğru ve erişilebilir. Bedeli: tablo, göç, temizlik işi ve kalıcı outbox;
   demonun ilk sürümü için fazla.
3. **Tek kopya, `Recreate`.** Durum tek yerdedir. Bedeli: dağıtımda ve pod ölümünde kısa kesinti, durum kaybı.

## Karar

Seçenek 3. Deployment `replicas: 1` ve `strategy: Recreate` taşır; eski pod tamamen kapanmadan yenisi başlamaz.
`terminationGracePeriodSeconds: 45`, kapanış bütçesinin (istekleri boşaltma 20 sn + outbox 10 sn ve 1 sn + Kafka
üreticisi 5 sn = 36 sn) üstündedir.

İkinci kopya olsa bile motor son çittir: ödeme kimliği süreç örneğinin `businessId`'si ve motorda tekillik açık,
karar da görev durumuna bağlı (`If-Match`, `412 PEX-3004`). Yani ikinci kopya yanlış bir yanıt kodu verebilir, ama
ikinci vaka ya da ikinci karar oluşturamaz.

## Sonuçlar

**Kazandıklarımız**

- Idempotency ve outbox sıralaması tek süreçte doğrudur; dağıtık kilit ya da paylaşılan depo yoktur.
- Kapanışta istekler ve kuyruktaki olaylar boşaltılır.

**Bedeli**

- Dağıtım ve pod yeniden başlatması sırasında API kısa süre yanıt vermez.
- Yeniden başlatmada idempotency kayıtları, önbellek ve kuyruktaki olaylar kaybolur. Kayıt motorda kalır.
- Yatay ölçek yoktur; demo yükü için gerekmez.

## Denetim

- `k8s/45` manifestinde `replicas: 1` ve `strategy: {type: Recreate}`; dosyanın başındaki yorum nedeni yazar.
- `e2e/run.sh`: aynı ödeme için ikinci açma `409 PEX-3002` (motor çiti, kopyadan bağımsız).

## Ne zaman yeniden bakılır

İkinci kopya ya da kesintisiz dağıtım gerektiğinde. Önce idempotency kayıtları ve outbox kümedeki PostgreSQL'e
taşınır, sonra `replicas: 2` ve `RollingUpdate` olur. Yol haritasında bu iş T14'tür; bu ADR'nin yerini o zaman yeni
bir ADR alır.
