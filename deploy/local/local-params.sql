-- 本机开发用的服务地址。全新数据库里这些参数是字符串 'null'，zixuan-server 会因此拒绝启动。
-- 执行后需要清掉 Redis 里的参数缓存（见 deploy/local/README.md）。
UPDATE sys_params SET param_value = 'ws://127.0.0.1:8000/zixuan/v1/' WHERE param_code = 'server.websocket';
UPDATE sys_params SET param_value = 'http://127.0.0.1:8002/zixuan/ota/' WHERE param_code = 'server.ota';
UPDATE sys_params SET param_value = 'http://127.0.0.1:8001' WHERE param_code = 'server.fronted_url';
UPDATE sys_params SET param_value = '紫萱' WHERE param_code = 'server.name' AND param_value = 'xiaozhi-esp32-server';
