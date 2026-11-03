# ADR-0003: REST gövdeleri protobuf, alan olayları Avro

- **Durum:** Kabul edildi
- **Tarih:** 2026-11-02
- **Kapsam:** `app/payment-exception-api/src/main/proto/bpo/exceptions/v1/`,
  `src/main/resources/avro/payment-exception-event.avsc`, `src/test/resources/avro/baseline/`,
  `src/main/resources/catalog/exception-catalog.yaml`

## Bağlam

API'nin iki türlü sözleşmesi var: istemcilerin çağırdığı REST uçları (vaka, kuyruk, karar, katalog) ve
yayımlanan alan olayları. İkisi de başka ekiplerin kodunun dayanacağı yayımlanmış dildir (published language).

Önemli nitelikler:

- **Sözleşmenin sıkılığı:** sözleşmede olmayan alan sessizce kabul edilmemeli.
- **Evrim:** alan eklemek eski istemciyi ve eski tüketiciyi kırmamalı.
- **Okunabilirlik:** demoda ve hata ayıklamada gövde `curl` ile okunabilmeli.
- **Hata dili:** her hata makinenin işleyebileceği bir kod ve insanın okuyabileceği bir kayıt taşımalı.

## Değerlendirilen seçenekler

1. **JSON + OpenAPI her yerde.** En tanıdık. Bedeli: şema elle senkron tutulur; olaylarda şema evrimi denetimi
   kendiliğinden gelmez.
2. **gRPC.** Sıkı sözleşme. Bedeli: tarayıcı ve `curl` ile demo zor; Keycloak ve port-forward akışı karmaşıklaşır.
3. **REST üzerinde protobuf (ikili ve proto3 JSON), olaylarda Avro.** Sözleşme `.proto` dosyasındadır; aynı mesaj
   ikili (`application/x-protobuf`) ya da JSON (`application/json`) gelir. Olaylarda Avro'nun okuyucu/yazıcı şema
   çözümü ve Kafka ekosistemindeki Schema Registry desteği kullanılır. Bedeli: iki şema dili.

## Karar

Seçenek 3.

- REST gövdeleri protobuf'tur; JSON ayrıştırıcı sözleşmede olmayan alanı reddeder, ikili mesajdaki bilinmeyen alan
  da reddedilir (`400 PEX-1008`). Protobuf enum'larının sıfır değeri `*_UNSPECIFIED`'dır ve alana girmez.
- Alan olayları Avro single-object encoding ile yazılır (`C3 01`, şemanın 64 bitlik parmak izi, kayıt). Avro
  enum'larının `UNKNOWN` varsayılanı, eski okuyucunun yeni sembolü okuyabilmesi içindir.
- Hatalar RFC 9457 `application/problem+json`'dır; `type` alanı hata kodu deposundaki kaydı gösterir
  (`/api/v1/exception-catalog/errors/PEX-NNNN`). Katalog 10 istisna durumu (EXC) ve 32 hata kodu (PEX) taşır.
- Value object'lerin kuralları (ör. `PaymentId` = `demo-tr-...`) alanda bir kez yazılır; protobuf ve Avro eşlemesi
  adaptörlerdedir.

## Sonuçlar

**Kazandıklarımız**

- Sözleşme tek kaynaktan gelir ve kodla birlikte derlenir.
- Proto3 JSON sayesinde demo `curl` ile okunur; üretimde ikili gövde aynı sözleşmeyle çalışır.
- Olay şemasının uyumluluğu testte denetlenir; tüketici kırılmadan alan eklenir.
- Hata kodları kataloğa bağlıdır; istemci `code` ve `retryable` alanına göre davranır.

**Bedeli**

- İki şema dili (proto ve avsc) ve iki eşleyici bakımı.
- OpenAPI belgesi kendiliğinden yoktur; yol haritasında `.proto`'dan üretilmesi T10'dur.
- Single-object encoding'in parmak izi Schema Registry'deki şema kimliği değildir; Redpanda ile gelince
  Confluent wire format'ına geçiş ya da Registry kaydı ayrıca kararlaştırılır (T9).

## Denetim

- `AvroEventCodecTest`: yayımlanan şema sürümü (`baseline/payment-exception-event-1.0.avsc`) ile güncel şema iki
  yönde de uyumlu olmak zorunda.
- REST adaptör testleri (`ProtoMapperTest`, `PaymentExceptionApiTest`): bilinmeyen alan `400 PEX-1008`,
  `*_UNSPECIFIED` reddi.
- `YamlCatalogSourceTest`: tekrarlanan kod, eksik zorunlu alan ya da bilinmeyen alan uygulamanın başlamasını durdurur.
- `ArchitectureTest`: protobuf yalnızca REST adaptöründe, Avro yalnızca olay adaptöründe.

## Ne zaman yeniden bakılır

Dış istemciler protobuf istemcisi kuramıyorsa (yalnızca OpenAPI tüketebiliyorsa) ya da kurum standardı bir olay
formatı (ör. CloudEvents üzerinde JSON Schema) şart koşarsa.
