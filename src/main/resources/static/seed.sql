#generate password 123456 user

INSERT INTO mydockerdb.users
(id, username, password, `role`, created_time, updated_time)
VALUES(1, 'admin', '$2a$10$YrSthOgMjXYgAd7X4SQ8tuLUThxwcop3cZmmzHSfZ.vBQ2A74lt8G', 'ADMIN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO mydockerdb.users
(id, username, password, `role`, created_time, updated_time)
VALUES(2, 'user', '$2a$10$YrSthOgMjXYgAd7X4SQ8tuLUThxwcop3cZmmzHSfZ.vBQ2A74lt8G', 'USER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);


# create new event
INSERT INTO mydockerdb.lottery_event
(id, name, is_active, setting_amount, remain_amount, created_time, updated_time)
VALUES({even_id}, 'test_event_1', 1, 100, 100, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);


# create prize

INSERT INTO mydockerdb.lottery_prize (lottery_event_id,name,rate,amount,created_time,updated_time) VALUES
	 ({even_id},'small',0.20,100,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
	 ({even_id},'medium',0.20,80,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
	 ({even_id},'big',0.10,70,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);

# grant to user

INSERT INTO mydockerdb.user_lottery_quota
(id, uid, lottery_event_id, draw_quota, created_time, updated_time)
VALUES(1, 2, {even_id}, 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);