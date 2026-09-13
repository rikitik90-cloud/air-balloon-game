FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY . .
RUN javac BalloonGame.java
CMD java BalloonGame $PORT
