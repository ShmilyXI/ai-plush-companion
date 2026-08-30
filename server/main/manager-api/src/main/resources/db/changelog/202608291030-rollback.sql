-- liquibase formatted sql

-- changeset codex:202608291030-rollback
DROP TABLE IF EXISTS ai_companion_conversation_turn;
DROP TABLE IF EXISTS ai_companion_conversation;
