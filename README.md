# demo-camunda-k8s

**Camunda 8.10** üzerinde **TR BKM FAST istisna süreci** (P2 `pay-instant-exception`) demosu. Yerel bir
Kubernetes kümesine (varsayılan: Docker Desktop) **tek komutla** kurulur. Her bileşen ayrı bir servis olarak
çalışır: Camunda 8.10 orkestrasyon kümesi, Connectors, Management Identity, Camunda Hub, Keycloak, PostgreSQL,
dört simüle sağlayıcı ve gerçek ödeme worker'ları.

```bash
./install.sh
```

Yerel ve geçici bir demodur; yalnızca **sentetik veri** kullanır. Hedef üretim topolojisi değildir (bkz. [Hedef topolojiden farklar](#hedef-topolojiden-farklar)).

---

## İçindekiler

1. [Hızlı başlangıç](#hızlı-başlangıç)
2. [Ön koşullar](#ön-koşullar)
3. [Mimari (UML)](#mimari-uml)
4. [Kurulum akışı](#kurulum-akışı)
5. [P2 süreci ve örnek veri](#p2-süreci-ve-örnek-veri)
6. [Demo senaryosu](#demo-senaryosu)
7. [Test durumu](#test-durumu)
8. [Güvenlik modeli](#güvenlik-modeli)
9. [Komutlar ve ayarlar](#komutlar-ve-ayarlar)
10. [Sorun giderme](#sorun-giderme)
11. [Depo yapısı](#depo-yapısı)
12. [Hedef topolojiden farklar](#hedef-topolojiden-farklar)

---

## Hızlı başlangıç

```bash
git clone https://github.com/COPUR/demo-camunda-k8s.git
cd demo-camunda-k8s
./install.sh          # ilk kurulum uzun sürer (imajlar, Gradle, Helm şeması iner); süre makineye göre değişir
./open.sh             # ayrı bir terminalde açık bırakın; arayüzler yalnız 127.0.0.1'de
```

Giriş: kullanıcı adı **`presenter`**. Şifre kurulumda rastgele üretilir ve yalnızca bir Kubernetes Secret'ında
durur; `install.sh` sonunda okuma komutunu yazdırır.

| Arayüz | Adres | Ne gösterir |
|---|---|---|
| Operate | http://localhost:8080/operate | Süreç örnekleri, izlenen yol, değişkenler, bekleme durumları, olay kayıtları |
| Tasklist | http://localhost:8080/tasklist | `pay-tr-fast-ops` ve `pay-tr-fast-supervisor` kuyruklarındaki kararlar |
| Hub | http://localhost:8070 | BPMN, DMN ve form modelleri (8.10'da Web Modeler + Console = Hub) |
| Identity | http://localhost:8084 | Kullanıcılar, roller, istemciler |
| Keycloak | http://localhost:18080/auth | OIDC sağlayıcı (yönetici konsolu demo için gerekmez) |

Durum kontrolü: `./status.sh`. Her şeyi silmek için: `./uninstall.sh`.

---

## Ön koşullar

| Gereksinim | Neden | Kontrol |
|---|---|---|
| Docker Desktop, **Kubernetes açık** | Küme ve imaj derlemesi | `kubectl --context docker-desktop get nodes` → `Ready` |
| Docker belleği **≥ 8 GB** (12 GB önerilir), **6 CPU** önerilir | Camunda, Hub, Keycloak, PostgreSQL ve 5 Java servisi | `docker info --format '{{.MemTotal}} {{.NCPU}}'` |
| `kubectl`, `helm` 3.19+ veya 4.x, `openssl`, `curl` | Kurulum betikleri | macOS: `brew install kubectl helm openssl` |
| Boş disk ≈ 10 GB | Camunda imajları ve derleme önbelleği | `docker system df` |
| Windows'ta **WSL 2** (Ubuntu) | Betikler bash; Git Bash yol dönüştürmesi `docker run -v` bağlamalarını bozar | Docker Desktop > Settings > Resources > WSL integration açık; depoyu WSL içinde (`~/`) klonlayın |

**JDK veya Gradle gerekmez:** uygulama imajı Docker içinde JDK 23 ile derlenir ve test edilir; `keytool` da
çalışma zamanı imajının içinde koşar.

Kümede başka iş yükleri belleğin büyük kısmını istiyorsa pod'lar `Pending` kalabilir; `install.sh` bunu uyarı
olarak bildirir.

---

## Mimari (UML)

### Bileşen diyagramı

```mermaid
flowchart LR
  subgraph host["Sunucu makinesi (127.0.0.1)"]
    U["Presenter<br/>tarayıcı"]
  end

  subgraph ns["Namespace bpo-demo-ms"]
    direction LR
    subgraph camunda["Camunda 8.10 (Helm şeması 15.1.1)"]
      Z["camunda-zeebe<br/>broker + gateway<br/>Operate · Tasklist · REST v2"]
      I["camunda-identity"]
      H["camunda-web-modeler-*<br/>Camunda Hub"]
      C["camunda-connectors"]
    end
    KC["Keycloak 26.7<br/>realm camunda-platform"]
    PG[("PostgreSQL 17.6")]
    W["bpo-worker-payments<br/>P2 job worker'ları"]
    subgraph sims["Simüle sağlayıcılar (HTTPS + JWT)"]
      S1["sim-psg-tr<br/>FAST şema durumu"]
      S2["sim-acc<br/>provizyon yakala / çöz"]
      S3["sim-pay<br/>ödeme durumu, karar"]
      S4["sim-ntf<br/>müşteri bildirimi"]
    end
    L["demo-loader Job<br/>modeller + 9 ödeme"]
  end

  U -- "port-forward" --> Z & H & I & KC
  W -- "job aktivasyonu" --> Z
  W -- "client credentials" --> KC
  W -- "HTTPS + Bearer (sağlayıcı başına audience)" --> S1 & S2 & S3 & S4
  S1 & S2 & S3 & S4 -- "JWKS ile imza doğrulama" --> KC
  Z -- "RDBMS ikincil depolama" --> PG
  I & H & KC --> PG
  Z & I & H & C -- OIDC --> KC
  L -- "REST v2: deploy, start, message" --> Z
```

### Dağıtım diyagramı

```mermaid
flowchart TB
  subgraph mac["macOS host"]
    cli["install.sh · open.sh · status.sh · uninstall.sh<br/>özel kubeconfig, yalnız yerel API sunucusu"]
    subgraph dd["Docker Desktop VM"]
      img["İmaj bpo-payments-demo:local"]
      subgraph node["K8s node (kind modunda desktop-control-plane)"]
        subgraph nsx["bpo-demo-ms (restricted Pod Security, NetworkPolicy)"]
          ss1["StatefulSet postgres-0 + PVC"]
          ss2["StatefulSet camunda-zeebe-0 + PVC 2Gi<br/>1 broker · 1 partition"]
          d1["Deployment keycloak"]
          d2["Deployment camunda-identity"]
          d3["Deployment web-modeler-restapi + websockets"]
          d4["Deployment camunda-connectors"]
          d5["Deployment sim-psg-tr · sim-acc · sim-pay · sim-ntf"]
          d6["Deployment bpo-worker-payments"]
          j1["Job keycloak-clients"]
          j2["Job demo-loader"]
          sec[("Secrets: DB, OIDC, istemci, TLS CA, presenter")]
        end
      end
    end
  end
  cli --> nsx
  img -. "ctr images import (kind modu)" .-> d5 & d6
```

### Sınıf diyagramı: worker'ın hexagonal yapısı

```mermaid
classDiagram
  direction LR
  class CaptureHoldWorker
  class ReleaseHoldWorker
  class StatusInquiryWorker
  class MarkSettledWorker
  class MarkRejectedWorker
  class NotifyPaymentWorker
  class RecordExceptionDecisionWorker

  class HoldPort { <<interface>> }
  class SchemeStatusPort { <<interface>> }
  class PaymentExceptionPort { <<interface>> }
  class NotificationPort { <<interface>> }

  class AccHoldAdapter
  class PsgTrSchemeStatusAdapter
  class PayExceptionAdapter
  class NtfNotificationAdapter

  class ProviderHttp { HTTPS, Bearer, x-idempotency-key, durum eşleme }
  class IdempotencyKeys { paymentId:capture, paymentId:release }
  class OutputVariables { izin listesi, para / kişisel veri reddi, 8 KB }
  class JobErrors { 422 -> BPMN hatası, geçici -> retry }

  CaptureHoldWorker --> HoldPort
  ReleaseHoldWorker --> HoldPort
  StatusInquiryWorker --> SchemeStatusPort
  MarkSettledWorker --> PaymentExceptionPort
  MarkRejectedWorker --> PaymentExceptionPort
  RecordExceptionDecisionWorker --> PaymentExceptionPort
  NotifyPaymentWorker --> NotificationPort
  HoldPort <|.. AccHoldAdapter
  SchemeStatusPort <|.. PsgTrSchemeStatusAdapter
  PaymentExceptionPort <|.. PayExceptionAdapter
  NotificationPort <|.. NtfNotificationAdapter
  AccHoldAdapter --> ProviderHttp
  PsgTrSchemeStatusAdapter --> ProviderHttp
  PayExceptionAdapter --> ProviderHttp
  NtfNotificationAdapter --> ProviderHttp
  ProviderHttp --> IdempotencyKeys
  CaptureHoldWorker ..> OutputVariables
  CaptureHoldWorker ..> JobErrors
```

---

## Kurulum akışı

`install.sh` tek komuttur ve tekrar çalıştırılabilir: var olan Secret'lar korunur, geri kalan her şey yeniden
uygulanır.

```mermaid
flowchart TD
  A([./install.sh]) --> B{Ön kontrol<br/>araçlar, Docker, bellek/CPU, helm}
  B -- eksik --> X([Hata: eksik olan ve kurulum yolu])
  B -- tamam --> C[Küme seçimi<br/>yerel olmayan API sunucusu reddedilir]
  C --> D[Helm şeması indir, sha256 doğrula]
  D --> E[docker build: JDK 23 ile derle + birim testleri]
  E --> F{Node bir container mı?}
  F -- "evet (kind modu)" --> F1[docker save → ctr images import]
  F -- minikube --> F2[minikube image load]
  F -- hayır --> F3[Runtime Docker imajlarını görür]
  F1 & F2 & F3 --> G[Namespace, rastgele kimlik bilgileri,<br/>7 günlük demo CA ve sertifikalar]
  G --> H[ConfigMap: modeller, senaryolar, yükleyici]
  H --> I[PostgreSQL + Keycloak]
  I --> J[helm upgrade --install camunda --wait]
  J --> K[Job keycloak-clients]
  K --> L[Simülatörler + worker]
  L --> M[Job demo-loader: deploy, 9 örnek, 3 uyandırma]
  M --> N{acc holdsCaptured = 4?}
  N -- evet --> O([Hazır: adresler ve şifre komutu])
  N -- hayır --> P([Uyarı: ./status.sh])
```

### Kurulum dizisi

```mermaid
sequenceDiagram
  autonumber
  actor P as Presenter
  participant S as install.sh
  participant D as Docker
  participant K as Kubernetes API
  participant HM as Helm
  participant KC as Keycloak
  participant Z as Camunda 8.10
  P->>S: ./install.sh
  S->>D: docker info (bellek, CPU)
  S->>K: kubeconfig --minify (yalnız yerel)
  S->>D: docker build -f app/Dockerfile (JDK 23, test)
  S->>D: docker save | ctr import (node)
  S->>K: Secrets, TLS, ConfigMaps
  S->>K: postgres, keycloak, NetworkPolicy
  S->>HM: upgrade --install camunda (15.1.1, --wait)
  HM->>Z: orchestration, connectors, identity, hub
  Z->>KC: realm camunda-platform
  S->>K: Job keycloak-clients
  K->>KC: servis istemcileri ve audience'lar
  S->>K: simülatörler ve worker
  S->>K: Job demo-loader
  K->>Z: /v2/deployments, /v2/process-instances, /v2/messages/publication
  S->>K: logs sim-acc (holdsCaptured)
  S-->>P: adresler + şifre okuma komutu
```

---

## P2 süreci ve örnek veri

`pay-instant-exception`, FAST'ten cevabı gelmeyen bir anlık ödemeyi devralır. Süreç talimatı **asla yeniden
göndermez**; yalnızca ağ geçidinin (`psg-tr`) kaydını okur.

### Durum makinesi

```mermaid
stateDiagram-v2
  [*] --> SemaKaydiOku: Payment.ExceptionRaised
  SemaKaydiOku --> ProvizyonYakala: ACSC
  SemaKaydiOku --> ProvizyonCoz: RJCT
  SemaKaydiOku --> Bekle: PDNG (okuma < 4)
  Bekle --> SemaKaydiOku: Msg_SchemeStatusReported veya PT30S / PT2M / PT10M
  SemaKaydiOku --> OpsKuyrugu: 4 okumadan sonra hâlâ PDNG
  ProvizyonYakala --> Yerlesti: acc.capture-hold (pivot)
  ProvizyonCoz --> Reddedildi: acc.release-hold
  OpsKuyrugu --> SupervizorKuyrugu: yükselt
  OpsKuyrugu --> KararKaydi: SETTLE / RELEASE
  SupervizorKuyrugu --> KararKaydi: SETTLE / RELEASE
  SupervizorKuyrugu --> Devredildi: süre doldu (EXPIRED)
  KararKaydi --> Yerlesti: pay doğrular
  KararKaydi --> Reddedildi: pay doğrular
  KararKaydi --> Devredildi: HANDED_BACK
  Yerlesti --> [*]
  Reddedildi --> [*]
  Devredildi --> [*]
  SemaKaydiOku --> Incident: bilinmeyen durum
  ProvizyonYakala --> Incident: 410 HOLD_EXPIRED
```

### ACSC yolu

```mermaid
sequenceDiagram
  autonumber
  participant Z as Camunda (P2)
  participant W as bpo-worker-payments
  participant KC as Keycloak
  participant G as sim-psg-tr
  participant A as sim-acc
  participant Y as sim-pay
  participant N as sim-ntf
  Z->>W: job: şema durumunu oku
  W->>KC: token (client_credentials, aud-svc-psg-tr)
  W->>G: GET durum (Bearer, x-fapi-interaction-id)
  G-->>W: ACSC
  W-->>Z: schemeStatus=ACSC
  Z->>W: job: provizyonu yakala
  W->>A: POST /holds/{paymentId}/capture (x-idempotency-key = paymentId:capture)
  A-->>W: journalRef
  Note over W,A: Kayıp cevapta tekrar: aynı anahtar → aynı journalRef, tek defter kaydı
  W-->>Z: journalRef
  Z->>W: job: pay yerleşti
  W->>Y: POST settled (journalRef)
  Z->>W: job: müşteriyi bilgilendir
  W->>N: POST bildirim
  W-->>Z: notificationRef
```

### Örnek veri (`sample-data/scenarios.json`)

| Ödemeler | psg-tr cevabı | Sonuç |
|---|---|---|
| `demo-tr-acsc-001..003` | ACSC | provizyon tek kez yakalanır, pay yerleşti, müşteri bilgilenir |
| `demo-tr-rjct-001..002` | RJCT | provizyon çözülür, pay reddedildi, müşteri bilgilenir |
| `demo-tr-pdng-001` | önce PDNG, sonra ACSC | bekler, olay uyandırır, ikinci okuma yerleştirir |
| `demo-tr-unres-001..003` | her okumada PDNG | dört okuma, sonra TR FAST operasyon kuyruğunda görev |

Beklenen: **9 örnek; 4 yerleşti, 2 reddedildi, 3 Tasklist'te karar bekliyor.** Yükleyici
`Msg_SchemeStatusReported` mesajını üç kez yayımlar (olay köprüsünün yapacağı gibi); böylece demo gerçek
zamanlayıcıları beklemez.

**Camunda yalnızca süreç durumunu tutar:** kimlik, enum, zaman damgası. Tutar, IBAN, isim veya iş kuralı sürece
girmez; `OutputVariables` bunları çalışma anında reddeder.

---

## Demo senaryosu

1. **Operate:** `pay-instant-exception`, 9 örnek. Yerleşmiş bir örneği açın: yol `Task_CaptureHold` üzerinden
   geçer, değişkenler yalnızca kimlik ve enum.
2. **Operate, bekleme durumları (8.10):** bekleyen örneklerde sürecin neyi beklediği görünür.
3. **Tasklist:** belirsiz kalan üç ödeme. Birini yerleştirin, birini reddedin, birini süpervizöre yükseltip
   orada kapatın.
4. **Hub:** P2 modeli, `lib-await-external-status` ve iki DMN.
5. **Güvenlik:** `kubectl -n bpo-demo-ms logs deploy/sim-psg-tr` kabul edilen audience'ı ve çağrı sayısını
   gösterir; geçerli token'ı olmayan çağrı 401 veya 403 alır.

---

## Test durumu

| Ortam | Durum |
|---|---|
| k3s (Linux sandbox, `DEMO_CLUSTER=current`, `DEMO_IMAGE_LOAD` ile) | `./install.sh` baştan sona çalıştı; 14 pod çalışıyor, doğrulama 4 bloke tahsilini gördü |
| Docker Desktop, kind modu (macOS) | Temiz klondan `./install.sh` baştan sona çalıştı (çıkış kodu 0); 12 pod çalışıyor, doğrulama 4 bloke tahsilini gördü |
| Docker Desktop kubeadm modu, OrbStack, Rancher Desktop, minikube | Denenmedi |

Süreler yaklaşıktır ve ağa, makineye göre değişir.

---

## Güvenlik modeli

| Konu | Demoda |
|---|---|
| Kimlik bilgileri | `install.sh` içinde `openssl rand` ile üretilir, yalnızca Secret'larda durur, hiçbir çıktıya yazılmaz |
| İnsan girişi | Keycloak OIDC, kullanıcı `presenter` |
| Servis girişi | Client credentials; sağlayıcı başına audience (`aud-svc-psg-tr`, `aud-svc-acc-accounts-ledger`, `aud-svc-pay-initiation-settlement`, `aud-svc-ntf`) |
| Sağlayıcı tarafı | Simülatörler imza, issuer, süre, audience ve çağıranı (azp) doğrular |
| Taşıma | Keycloak ve simülatörler HTTPS (7 günlük demo CA); Camunda ↔ Keycloak/PostgreSQL namespace içinde HTTP |
| Ağ | Varsayılan ret NetworkPolicy ve akış başına izinler. Tarayıcı portları (8080, 8084, 8081, 8060) ile Keycloak (18080, 8443) her kaynaktan, başka namespace'ler dahil, erişilebilir. Docker Desktop NetworkPolicy uygulamaz; orada sınır ClusterIP servisleri ve 127.0.0.1 port-forward'larıdır. |
| Pod'lar | Restricted Pod Security, root değil, tüm capability'ler düşürülmüş |
| Erişim | `open.sh` yalnızca 127.0.0.1'e bağlanır |
| Küme | Betikler yalnızca API sunucusu bu makinede olan kümeye çalışır, kendi kubeconfig kopyasıyla |
| İmajlar | Yerel imaj dışındakiler digest ile sabit; Helm şeması sha256 ile doğrulanır |

---

## Komutlar ve ayarlar

| Komut | Ne yapar |
|---|---|
| `./install.sh` | Kurar veya günceller (tekrar çalıştırılabilir) |
| `./open.sh` | Arayüzleri 127.0.0.1'de yayınlar; Ctrl-C ile kapanır |
| `./status.sh` | Pod'lar, yükleyici sonucu, simülatör sayaçları, worker uyarıları |
| `./uninstall.sh` | Helm sürümünü ve namespace'i siler (veri ve Secret'lar dahil) |

| Ortam değişkeni | Varsayılan | Anlamı |
|---|---|---|
| `DEMO_CLUSTER` | `auto` | `auto`: varsa `docker-desktop`, yoksa geçerli context · `docker-desktop` · `current` |
| `DEMO_IMAGE_LOAD` | otomatik | İmajı kümeye aktaran komut (stdin'den arşiv okur), ör. `"kind load image-archive /dev/stdin"`, `"sudo k3s ctr images import -"` |
| `DEMO_SKIP_TESTS` | `false` | `true`: imajı birim testleri koşmadan derler |
| `DEMO_BUILD_CA` | boş | TLS denetleyen kurumsal proxy arkasında, imaj derlemesinin güveneceği CA dosyası (tek sertifika ya da paket) |

Başka yerel kümeler (kind, k3s, minikube, OrbStack, Rancher Desktop): API sunucusu bu makinedeyse
`DEMO_CLUSTER=current ./install.sh`.

---

## Sorun giderme

| Belirti | Neden | Çözüm |
|---|---|---|
| Simülatör veya worker `ErrImageNeverPull` | Küme Docker'ın imajlarını görmüyor | Kind modu otomatik algılanır; başka kümelerde `DEMO_IMAGE_LOAD` verin |
| Pod'lar `Pending` | Bellek yetersiz | Docker belleğini artırın veya kümedeki diğer iş yüklerini durdurun |
| `helm ... --wait` zaman aşımı | İlk imaj indirmesi yavaş veya bellek yetersiz | `kubectl -n bpo-demo-ms get pods`, sonra `./install.sh` tekrar |
| Derlemede `PKIX path building failed` | Kurumsal proxy TLS'i denetliyor | Proxy CA'sını `DEMO_BUILD_CA=/yol/ca.crt ./install.sh` ile verin |
| `the payments worker has stalled ... restarting it once` | Worker ilk yüklemede bazen job almayı bırakıyor (incident yok, job'lar `CREATED`) | Kendiliğinden düzelir: install.sh worker'ı bir kez yeniden başlatır, açık job'lar devam eder. İkincisinde de 4 bloke yoksa hata verir. |
| Pod'lar `Pending`, olayda `disk-pressure` | Docker'ın diski dolu | `docker system df`; gereksiz imajları temizleyin veya Docker disk sınırını artırın |
| `chart sha256 mismatch` | İndirme bozuk | `.cache/` silip tekrar deneyin |
| Giriş başarısız | Yanlış kullanıcı/şifre | Kullanıcı `presenter`; şifre Secret `demo-presenter` içinde |
| Sertifika hatası (7 gün sonra) | Demo CA süresi doldu | `./install.sh` tekrar: süresi bir günden az kalan CA'yı ve sunucu sertifikalarını yeniler, Keycloak'u yeniden başlatır |
| `$'\r': command not found` | Windows'ta CRLF ile klonlandı | `.gitattributes` LF'yi zorlar; eski klonu silip yeniden klonlayın |

```bash
./status.sh
kubectl -n bpo-demo-ms describe pod <pod>
kubectl -n bpo-demo-ms logs <pod> --tail=50
```

---

## Depo yapısı

```text
install.sh  open.sh  status.sh  uninstall.sh
lib/common.sh          küme seçimi, yerel API koruması, imaj aktarımı
helm/                  values-camunda.yaml, chart-lock.yaml (camunda-platform 15.1.1, sha256)
k8s/                   00 namespace · 10 postgres · 20 keycloak · 30 keycloak istemcileri
                       40 simülatörler + worker · 50 network policy · 60 yükleyici Job
models/                P2 BPMN, L3 kütüphane süreci, 2 DMN, 2 form
sample-data/           scenarios.json (9 sentetik ödeme), load.sh
app/                   Gradle çok modüllü uygulama ve çok aşamalı Dockerfile
  bpo-worker-kit/      idempotency anahtarları, çıktı korkuluğu, sağlayıcı HTTP, hata eşleme
  bpo-worker-payments/ P2 job worker'ları (handler → port → adapter)
  domain-simulator/    sağlayıcı sözleşmelerinin süreç içi taklidi
  payments-demo/       tek imaj: worker'lar veya bir simüle sağlayıcı (rol başına Deployment)
```

---

## Hedef topolojiden farklar

Bunlar bilinçli demo kısayollarıdır.

| Demo | Hedef (TR üretim) |
|---|---|
| 1 broker, 1 partition, replikasyon 1 | 3 broker, 6 partition, replikasyon 3, PDB minAvailable 2 |
| İkincil depolama PostgreSQL (RDBMS) | OpenSearch |
| Worker ve yükleyici admin rolünde | Her istemcinin kendi yetkileri |
| 7 günlük demo CA, Camunda iç trafiği HTTP | Mesh, katı mTLS ve DPoP |
| Sağlayıcılar simülatör | Yayımlanmış katalog sözleşmeleri (`acc`, `pay`, `psg-tr`, `ntf`) |
| Connectors ve Hub açık | Kapalı; her dış çağrı sözleşmesi olan bir worker'dan geçer |

Kaynak: `COPUR/neo-fintechbankx-bpo-process-orchestration` commit `b8c4f50` (`demo/microservices`). Bu
depoda eklenenler: sağlayıcı başına worker kapsamları (`BPO_AUTH_SCOPE_*`), kind modu imaj aktarımı, Docker
içinde derleme (hostta JDK gerekmez), kurulum sonrası doğrulama ve `status.sh`.
