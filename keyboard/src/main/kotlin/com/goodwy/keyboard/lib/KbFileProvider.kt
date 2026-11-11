package com.goodwy.keyboard.lib

/** Свой класс FileProvider: у приложения уже есть androidx FileProvider с другим адресом — одинаковые имена в манифесте конфликтуют. */
class KbFileProvider : androidx.core.content.FileProvider()
