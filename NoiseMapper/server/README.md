# Server

using docker-compose (recommended):
```console
docker-compose up
```

or using just docker:

```console
docker build -t masss_server .
docker run --name MaSSS_Server -i -p 5002:5002 -v $PWD:/app masss_server 
```
