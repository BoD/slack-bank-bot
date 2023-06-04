# slack-bank-bot

Monitor your bank accounts in Slack.

## Docker instructions

### Building and pushing the image to Docker Hub

```
docker image rm bodlulu/slack-bank-bot:latest
DOCKER_USERNAME=<your docker hub login> DOCKER_PASSWORD=<your docker hub password> ./gradlew dockerPushImage
```

### Running the image

```
docker pull bodlulu/slack-bank-bot
docker run bodlulu/slack-bank-bot -n <nordigen refresh token> -s <slack token> -c <slack channel> "Account 1 name:account1id" "Account 2 name:account2id" 
```
