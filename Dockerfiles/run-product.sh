#!/usr/bin/env bash
nm=eso-product
mkdir dc-eso-kr/Addons/Destinations/ -p
docker create -m 600m --memory-swap=1G --name $nm --rm -ti -v $PWD/dc-eso-kr:/root/eso_server whya5448/dc-eso-kr:product
docker cp id_rsa ${nm}:/root/.ssh/
docker start -ai ${nm}