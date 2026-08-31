-- uq_messages_chat_turn_id 原本只按 chat_turn_id 唯一，但一个回合要写两条消息：
-- submit() 落用户提问，completeWithMessage() 落助手回答，两者带同一个 chat_turn_id。
-- 于是助手回答的 INSERT 必然撞唯一约束，回答永远存不下来。
-- V62 的注释写的是"重试的 worker 不能重复创建同一条用户或助手消息"，
-- 按角色区分才是原意，因此改为 (chat_turn_id, role) 唯一。
DROP INDEX IF EXISTS uq_messages_chat_turn_id;

CREATE UNIQUE INDEX uq_messages_chat_turn_id_role
  ON messages (chat_turn_id, role) WHERE chat_turn_id IS NOT NULL;
