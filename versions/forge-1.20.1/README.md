# TraduzMods — Forge 1.20.1

Variante da **3.0.0-alpha.1**, nas edições Lite e Normal. Loader mínimo declarado: 47.4.0; bytecode Java 17. O núcleo e os testes são sincronizados com as demais plataformas.

As duas edições passaram em 106 testes por artefato no PC do autor (Ryzen 5 5600X, 16 GB de RAM, RTX 5060 de 8 GB, Windows 11 Pro). A suíte usa um provedor simulado. Não foi realizado um novo benchmark do modelo nem teste visual completo em cada modpack.

Inclui o ajuste de dependência para Forge 47.4.0 e pack.mcmeta no formato 15. O JAR distribuído é reobfuscado para SRG e contém o refmap e MixinConfigs. A remoção de assinaturas ocorre apenas na cópia remapeada utilizada pelos testes isolados.

Compile na pasta do loader com Gradle executado no JDK 25 e JDKs 17 e 21 disponíveis para toolchains:

```powershell
.\gradlew.bat build integrationTest -Pedition=normal -Pmod_version=3.0.0-alpha.1-normal
.\gradlew.bat build integrationTest -Pedition=lite -Pmod_version=3.0.0-alpha.1-lite
```

Veja as [notas e limites da versão](../../docs/CHANGELOG-3.0.0-alpha.1.md) e os [portes históricos originais da alpha.4](../../releases/2.0.0-alpha.4/ports/PORTS.md).
