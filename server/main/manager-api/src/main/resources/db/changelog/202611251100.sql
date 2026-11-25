-- App 用户自己设的昵称和头像。单独一张表：登录和校验 token 时读 sys_user，不会顺带读出头像图片。
CREATE TABLE app_user_profile (
  user_id bigint NOT NULL COMMENT '用户id',
  nickname varchar(20) NULL COMMENT '昵称；为空时界面显示打码的手机号',
  avatar mediumtext NULL COMMENT '头像：浏览器裁成正方形、缩小后的 data URL（image/jpeg、png 或 webp）',
  update_date datetime COMMENT '更新时间',
  PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='App用户资料';
