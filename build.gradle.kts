// Все версии плагинов объявлены здесь (apply false), подмодули применяют без версий.
// Kotlin JVM и Kotlin Android — один и тот же classpath-артефакт, поэтому версию
// нельзя задавать в обоих подмодулях независимо (конфликт "already on the classpath").
plugins {
    kotlin("jvm") version "1.9.24" apply false
    kotlin("android") version "1.9.24" apply false
    id("com.android.application") version "8.5.2" apply false
}
