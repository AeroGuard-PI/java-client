# Cliente Java

Coleta download e upload por interface usando Java 25, OSHI 7.7.2, records e JDBC. O Maven Shade inclui as dependências no JAR.

A instalação é feita pelo script-instalacao-cliente.sh do repositório [script-instalacao-pi](https://github.com/AeroGuard-PI/script-instalacao-pi). Ele baixa este repositório e script-python separadamente e constrói as imagens Docker. A empresa vem do login e o MAC é obtido automaticamente. O Java recebe a configuração preparada pelo instalador.

Para compilar no ambiente de desenvolvimento:

```bash
mvn clean package
```

O resultado é `target/aeroguard-java.jar`. O Dockerfile faz essa compilação usando JDK 25 e executa o JAR em uma JRE 25.

A taxa usa `(delta de bytes × 8) / segundos decorridos / 1.000.000`, em Mbps. A primeira leitura prepara a referência. Reset de contador ou troca da interface descarta a comparação anterior.

`interfaceNome`, como ens5 ou eth0, é o nome local da interface. O MAC identifica a EC2; a coleta inclui as interfaces disponíveis e não se limita àquela usada no cadastro.

Uma thread agenda os ciclos a cada cinco segundos. Records e listas imutáveis transportam as leituras, e o histórico é protegido. As capturas são gravadas em lote e transação. A configuração mais recente com disponibilidade 0 impede novas inserções sem ser reativada pelo agente.

As duas FKs de configuração em captura recebem o ID da configuração consultada. Uma falha na leitura ou no banco é registrada e os ciclos seguintes continuam. Não há reenvio de amostras perdidas.
