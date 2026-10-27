# Cliente Java AeroGuard

Coleta download e upload por interface, em Mbps. Usa Java 25, OSHI 7.7.2, records, JDBC.

Para instalar em um servidor, use `sudo bash instalar-cliente.sh` na raiz do pacote completo. O JAR já está incluído e o instalador prepara o Java e o serviço. O Python é instalado junto e coleta CPU, memória e disco.

## Desenvolver

```bash
mvn clean package
java -jar target/aeroguard-java.jar --interfaces
```

O resultado é `target/aeroguard-java.jar`, com as dependências incluídas. Para executar manualmente, forneça DB_HOST, DB_PORT, DB_USER, DB_PASSWORD, EMPRESA_CNPJ e MAC_ADDRESS no ambiente e rode `java -jar target/aeroguard-java.jar`.

O instalador salva as variáveis em um arquivo de ambiente do systemd. 

## Coletar

O MAC escolhido precisa existir no servidor. Java e Python usam o mesmo CNPJ e MAC; servidores diferentes recebem seus próprios MACs. Hostname não identifica a máquina.

A aplicação busca empresa, máquina, componente Rede e configuração no banco. Cadastra a máquina se necessário e cria a configuração de Rede apenas quando ainda não existir. A configuração mais recente com disponibilidade 0 é preservada e impede novas inserções daquele componente.

`interfaceNome` é o nome dado pelo sistema à interface, como `eth0` ou `ens18`. Não é o hostname. O MAC usado no cadastro não restringe a coleta a uma única interface.

Uma thread executa os ciclos a cada cinco segundos. Os contadores acumulados são comparados com a leitura anterior:

```text
Mbps = (bytes atuais - bytes anteriores) × 8 / segundos decorridos / 1.000.000
```

A primeira leitura prepara a referência. O cálculo usa o tempo efetivamente decorrido. Reset de contadores ou troca de interface descarta a comparação anterior. Os records e as listas imutáveis transportam os valores; o acesso ao histórico também é protegido.

Os registros são gravados por batch em uma transação. As duas colunas configuracao_maquina_* recebem o ID da configuração, conforme as FKs do SQL recebido. Um erro desfaz a transação e o agendamento continua.