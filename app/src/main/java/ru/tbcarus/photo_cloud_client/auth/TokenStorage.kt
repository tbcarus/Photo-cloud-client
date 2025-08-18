package ru.tbcarus.photo_cloud_client.auth

interface TokenStorage {
    fun getTokens(): Tokens?
    fun saveTokens(tokens: Tokens)
    fun clear()
}