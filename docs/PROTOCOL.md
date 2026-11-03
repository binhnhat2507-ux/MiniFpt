# Giao thức MiniFTP

Đây là giao thức nhị phân riêng trên một TCP socket, không triển khai RFC FTP. Hai đầu dùng Java `DataInputStream`/`DataOutputStream`: `UTF` là `writeUTF`/`readUTF` (modified UTF-8 có tiền tố độ dài 2 byte), `int` 4 byte, `long` 8 byte big-endian và `boolean` 1 byte. Nội dung tệp là byte thô, không mã hóa thành chuỗi.

Cổng mặc định: `5000`. Mỗi kết nối có thời gian chờ đọc 30 giây phía server. `FTPClient` kết nối tối đa 5 giây và chờ đọc tối đa 30 giây. Kích thước tệp truyền tối đa 1 GiB.

## Xác thực và vòng đời

`FTPClient` hiện dùng một socket mới cho mỗi thao tác. Client có thông tin đăng nhập sẽ gửi `LOGIN` và đợi `OK` trước lệnh tệp. Server cũng hỗ trợ nhiều lệnh tuần tự trên cùng socket đã xác thực.

`REGISTER` chỉ tạo tài khoản, không đăng nhập cho socket đó. UI đăng ký rồi mở bước đăng nhập. Không có tài khoản mặc định. Mọi tài khoản đã đăng nhập dùng chung kho và cùng quyền thao tác.

| Lệnh client gửi | Server thành công trả |
| --- | --- |
| `UTF LOGIN`, `UTF username`, `UTF password` | `UTF OK` và đánh dấu socket đã đăng nhập |
| `UTF REGISTER`, `UTF username`, `UTF password` | `UTF OK` |
| `UTF PING` | `UTF OK`; không bắt buộc đăng nhập |
| `UTF LIST`, `UTF directory` | `UTF OK`, `int count`, rồi `count` bản ghi: `UTF name`, `long size`, `boolean directory` |
| `UTF MKDIR`, `UTF relativePath` | `UTF OK` |
| `UTF DELETE`, `UTF relativePath` | `UTF OK`; chỉ tệp hoặc thư mục rỗng |
| `UTF RENAME`, `UTF relativePath`, `UTF newLeafName` | `UTF OK`; giữ nguyên thư mục cha |
| `UTF QUIT` | Đóng socket, không có phản hồi |

Danh sách chỉ trả tên lá, không trả đường dẫn tuyệt đối; kích thước thư mục là `0`. Đường dẫn gốc trong `LIST` là chuỗi rỗng `""`. Các lệnh tệp khác không nhận đường dẫn rỗng.

Tất cả lệnh tệp yêu cầu xác thực trên server. Nếu chưa đăng nhập, server trả lỗi rồi đóng socket để dữ liệu tham số còn lại không bị hiểu thành lệnh tiếp theo. Đăng nhập sai cũng đóng kết nối.

## Upload

```text
Client → Server: UTF UPLOAD, UTF relativePath, long size
Server → Client: UTF READY
Client → Server: chính xác size byte nội dung
Server → Client: UTF OK
```

Server có thể trả `ERROR` thay cho `READY` trước khi nhận nội dung. Dữ liệu được ghi vào tệp tạm ẩn; chỉ sau khi nhận đủ mới chuyển sang tên đích. Nếu upload lỗi giữa chừng, server dọn tệp tạm, cố trả lỗi rồi đóng kết nối. Không ghi đè tệp đích đã có, kể cả nhiều client upload cùng tên.

## Download

```text
Client → Server: UTF DOWNLOAD, UTF relativePath
Server → Client: UTF OK, long size, chính xác size byte nội dung
```

Server mở tệp trước khi gửi tiêu đề thành công. Nếu lỗi trước tiêu đề, trả `ERROR`. Nếu dữ liệu đã bắt đầu rồi gặp lỗi, server đóng socket; không nối một chuỗi `ERROR` vào nội dung tệp. Client nhận đủ vào tệp tạm rồi mới chuyển sang tên đích, không ghi đè tệp local đã tồn tại.

## Lỗi và đường dẫn

Lỗi trước khi bắt đầu luồng byte tệp có dạng:

```text
UTF ERROR, UTF message
```

Thông báo cho client bằng tiếng Việt, không trả đường dẫn tuyệt đối hệ thống tệp server. Các lỗi lệnh đã đọc đủ tham số thường cho phép tiếp tục dùng kết nối; lỗi xác thực hoặc luồng truyền không còn đồng bộ sẽ đóng socket.

Đường dẫn dùng `/` giữa các thành phần và luôn tương đối với kho. Server từ chối đường dẫn tuyệt đối, `..`, tên ẩn bắt đầu bằng `.`, thành phần rỗng, `\`, tên/ký tự cấm và liên kết tượng trưng ở bất kỳ thành phần nào. Không thể truy cập `.accounts.properties` bằng các lệnh tệp. Đường dẫn tối đa 4096 ký tự, mỗi tên tối đa 120 ký tự và 255 byte UTF-8.

## Giới hạn triển khai

Mật khẩu và dữ liệu truyền trên TCP chưa có TLS. Hash PBKDF2 bảo vệ mật khẩu lưu trên đĩa, không mã hóa đường truyền. Dùng hai đầu trong mạng tin cậy; không coi đây là giao thức triển khai công khai ngoài Internet. Chưa có thương lượng phiên bản, tiếp tục truyền dở hoặc checksum tệp ở tầng ứng dụng.
