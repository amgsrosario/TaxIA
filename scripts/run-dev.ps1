# Arranca a aplicação em modo de desenvolvimento.
# Usa o perfil "dev" — nenhuma chave de API é necessária.
# Nunca adicione chaves reais a este ficheiro.
$env:SPRING_PROFILES_ACTIVE = "dev"
mvn spring-boot:run
