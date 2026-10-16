# TraduzMods — NeoForge 1.21.1

Variante da **3.0.0-alpha.1**, nas edições Lite e Normal. Loader mínimo declarado: 21.1.250; bytecode Java 21. O núcleo e os testes são sincronizados com as demais plataformas.

As duas edições passaram em 106 testes por artefato no PC do autor (Ryzen 5 5600X, 16 GB de RAM, RTX 5060 de 8 GB, Windows 11 Pro). A suíte usa um provedor simulado. Não foi realizado um novo benchmark do modelo nem teste visual completo em cada modpack.

Compile na pasta do loader com Gradle executado no JDK 25 e JDKs 17 e 21 disponíveis para toolchains:

```powershell
.\gradlew.bat build integrationTest -Pedition=normal -Pmod_version=3.0.0-alpha.1-normal
.\gradlew.bat build integrationTest -Pedition=lite -Pmod_version=3.0.0-alpha.1-lite
```

Veja as [notas e limites da versão](../../docs/CHANGELOG-3.0.0-alpha.1.md) e os [portes históricos originais da alpha.4](../../releases/2.0.0-alpha.4/ports/PORTS.md).
