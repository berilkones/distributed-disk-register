✔ Otomatik Dağıtık Üye Keşfi
Her node başlatıldığında 5555’ten başlayarak uygun bir port bulur.
Node, kendisini NodeInfo (host, port) olarak tanımlar.
Daha önce başlatılmış node’lara gRPC üzerinden Join isteği gönderir.
Join yanıtında dönen FamilyView ile mevcut küme üyeleri öğrenilir.
Tüm aktif üyeler NodeRegistry içerisinde tutulur.
✔ Lider Üye (Cluster Gateway)
İlk başlatılan node (port 5555) otomatik olarak lider kabul edilir.
Lider node:
gRPC servislerini sunar
Aynı zamanda TCP 6666 portu üzerinden dış dünya ile iletişim kurar
Dış dünyadan gelen komutlar lider tarafından işlenip kümeye yayılır.
✔ gRPC Tabanlı Aile (Family) Servisi
Aşağıdaki gRPC RPC’leri tanımlanmış ve kullanılmıştır:
Join(NodeInfo) → FamilyView
GetFamily(Empty) → FamilyView
ReceiveChat(ChatMessage) → Empty
GetMessage(GetRequest) → GetResponse
Tüm node’lar bu servisleri kullanarak birbirleriyle haberleşir.
✔ SET Komutu – Dağıtık Disk Registery (Yazma)
TCP üzerinden gelen SET <id> <content> komutu:
Lider node tarafından karşılanır
gRPC ChatMessage nesnesine dönüştürülür
Mesaj içeriği:
id (int64)
content
fromHost, fromPort
timestamp
Mesaj, tolerans değerine göre birden fazla node’a replike edilir.
Her node gelen veriyi diskine yazar:
Dosya adı: data_<port>.txt
Format: id:content
✔ GET Komutu – Dağıtık Okuma
TCP üzerinden gelen GET <id> komutu lider tarafından işlenir.
Lider node:
Önce kendi diskinde arama yapar
Bulamazsa diğer node’lara gRPC GetMessage isteği gönderir
İlgili id herhangi bir node’da bulunursa içerik TCP istemcisine döndürülür.
Hiçbir node’da bulunamazsa NOT_FOUND cevabı verilir.
✔ Replikasyon ve Hata Toleransı
Replikasyon sayısı tolerance.conf dosyasından okunur.
Dosya yoksa varsayılan tolerans değeri 2’dir.
Replikasyon sırasında:
Erişilemeyen node’lar atlanır
Diğer node’lara gönderim devam eder
Bu yapı sayesinde sistem kısmi node hatalarına dayanıklıdır.
✔ Yük Dengeleme (ID’ye Göre Dağıtım)
Replikasyon yapılacak node’lar port numarasına göre sıralanır.
Mesajın id değeri kullanılarak başlangıç node’u seçilir:
id % node_sayısı
Böylece yazma yükü node’lar arasında dengelenir.
✔ Sağlık Kontrolü (Health Check)
Node’lar belirli aralıklarla birbirlerini gRPC üzerinden kontrol eder:
GetFamily RPC çağrısı
Erişilemeyen node’lar NodeRegistry’den otomatik olarak çıkarılır.
Küme yapısı zamanla kendini günceller.
✔ İzleme ve Debug Desteği
Her node belirli aralıklarla konsola:
Kendi kimliğini
Family üyelerini
Üye sayısını
Tarih-saat bilgisini yazdırır
Bu çıktı sayesinde dağıtık yapı gözlemlenebilir.
Sonuç
Bu proje:
gRPC + Protobuf kullanarak dağıtık node haberleşmesini,
TCP tabanlı dış istemci entegrasyonunu,
Disk tabanlı dağıtık veri saklamayı,
Replikasyon, hata toleransı ve sağlık kontrolü mekanizmalarını
tek bir hibrit mimaride birleştirmektedir.
Sistem Programlama, Dağıtık Sistemler ve gRPC tabanlı uygulamalar için örnek bir referans niteliğindedir.
