# ADR-0002: İkili yazım (dual write) yok; olaylar outbox'tan ve süreçten çıkar

- **Durum:** Kabul edildi
- **Tarih:** 2026-11-02
- **Kapsam:** `app/payment-exception-api` (`adapter.out.events`: `OutboxEventPublisher`, `KafkaEventSink`,
  `LoggingEventSink`), ileride Redpanda ile gelecek durum olayları (`models/`, worker'lar)

## Bağlam

İstisna API'si bir karar aldığında iki şey olur: motordaki görev tamamlanır ve dış dünyaya bir alan olayı
(`PaymentExceptionEvent`) yayımlanır. Planlanan Redpanda senaryosunda ödeme durum olayları da yayımlanacak.

Bir isteğin içinde motora ve broker'a ayrı ayrı yazmak (dual write) iki yazmadan birinin başarısız olduğu anı açık
bırakır: görev tamamlanmış ama olay yok, ya da olay yayımlanmış ama görev tamamlanmamış. İkisi tek işlemde
birleştirilemez.

Önemli nitelikler: karar kaybolmamalı (kayıt motorda), broker yavaşsa ya da yoksa komut başarısız olmamalı,
tüketici aynı olayı iki kez alsa bile bir kez işlemeli.

## Değerlendirilen seçenekler

1. **İstek içinde motor + broker.** Basit görünür. Bedeli: kısmi başarı; broker kesintisi her komutu düşürür.
2. **Önce olay, motoru tüketici günceller.** Broker kayıt sistemi olur. Bedeli: [ADR-0001](0001-camunda-system-of-record.md)
   ile çelişir; API yazdığını hemen okuyamaz.
3. **Motora yaz, olayı outbox'a koy; ayrı bir iş parçacığı teslim etsin.** Komut yalnızca motorun başarısına
   bağlıdır. Bedeli: teslim en az bir kez; outbox kalıcı değilse süreç ölümünde kuyruk kaybolur.
4. **Olayı motorun exporter'ından üret.** Gerçek tek yazma. Bedeli: exporter ve dönüştürücü; demo için fazla.

## Karar

Seçenek 3, kalıcı olmayan bir outbox'la. API isteği içinde yalnızca motora yazar; `publish` olayı sınırlı bir
bellek kuyruğuna koyar, tek bir iş parçacığı sırayla, üstel geri çekilme ve tam jitter ile teslim eder. Olay
kimliği olgudan türetilir (isim tabanlı UUID), kayıt anahtarı ödeme kimliğidir (bölüm içinde sıra). Kafka üreticisi
`acks=all`, idempotent, uçuşta en fazla 5 istek. Broker'sız demoda olay log'a yazılır (`LoggingEventSink`).

Aynı ilke Redpanda'daki durum olayları için de geçerlidir: olay bir worker'ın yan etkisi olarak değil, BPMN'de
açık bir üretici görevi olarak yayımlanır; `eventId` idempotency anahtarı ve olay başına sıra numarasından
türetilir; gelen olaylar Zeebe mesaj TTL'i ve `messageId` ile ilişkilendirilir, polling yedek kalır, 3 denemeden
sonra DLQ.

## Sonuçlar

**Kazandıklarımız**

- Broker kesintisi komutu düşürmez ya da yavaşlatmaz; kayıt motordadır.
- Tüketici tekrarı olay kimliğiyle ayıklar; en az bir kez teslim güvenlidir.
- Durum olayları süreç modelinde görünür; olayın neden ve ne zaman çıktığı Operate'te izlenir.

**Bedeli**

- Outbox bellektedir: süreç ölürse kuyruktaki olaylar gider; kuyruk dolarsa en yeni olay düşer ve sayılır. Kayıt
  motorda kaldığı için karar kaybolmaz, yalnızca bildirimi kaybolabilir.
- Bellekteki outbox, API'yi tek kopyada tutan nedenlerden biridir ([ADR-0005](0005-exception-api-single-replica.md)).
- Olay, motordaki değişiklikten kısa bir süre sonra görünür (eventual).

## Denetim

- `OutboxEventPublisherTest`: sıra, geri çekilme, dolu kuyrukta düşürme ve kapanışta boşaltma.
- `KafkaEventSinkTest`: üretici ayarları ve başlıklar (`event-type`, `event-id`, `content-type`,
  `x-fapi-interaction-id`).
- `ArchitectureTest`: Avro ve Kafka yalnızca olay adaptöründe.

## Ne zaman yeniden bakılır

Olayların kaybolmaması gereken bir tüketici çıktığında ya da API'nin ikinci kopyası gerektiğinde: outbox kalıcı olur
(kümedeki PostgreSQL'de bir tablo ya da motorun exporter'ı). Yol haritasında bu iş T14'tür.
