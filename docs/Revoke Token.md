# Revoke Tokens


We can revoke both access & refresh token

```
curl --location 'http://localhost:9000/oauth2/revoke' \
--header 'Content-Type: application/x-www-form-urlencoded' \
--data-urlencode 'token=eyJraWQiOiJkMThhMWNmMC04OThhLTRkYWYtYWNiNS05OWQyNDNkNDllZWMiLCJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIwYWVkODQ5NC05NjA1LTQ3YTgtYThiMS1lM2VmMTYyYzFkYzgiLCJhdWQiOiIxIiwibmJmIjoxNjc4NTEwNjQzLCJzY29wZSI6WyJyZWFkIl0sInJvbGVzIjpbIlJPTEVfQURNSU4iXSwiaXNzIjoiaHR0cDovL2xvY2FsaG9zdDo5MDAwIiwiZXhwIjoxNjc4NTEzNjQzLCJpYXQiOjE2Nzg1MTA2NDN9.F1BwBvDUH1PQHG81zcivYSXwD7AfECpg1HRT3StvR7s2flIIVcc4ep3SPHtSm4iS3BHAoYSbNt4jSsHa4zKa6L8o5_fqL8DPbWq8SO4t2rY0rZdW1kt6Nlaw8blc1zAKjFL2o0tCahCbUozrtIlCepxGw6u6805rdTHDFRuVNSGdiIhO0eRTNwV7f0WHe6bC2qvvHqzMiRertk4eZe2R0x5QNzuHR9gwPUsCXsfp7qpEufJDiGsmuVd-JWw8hyZBzttg_xCeUr0KtIiUg_Zxo-clG_sgaZgE4XJtNvpNfnZV60vogfWEFa5300gWWHQAuWJT7JXpzqYfUO4I5rtsdg' \
--data-urlencode 'client_id=1' \
--data-urlencode 'client_secret=Thirumal'
```

![](./img/access-token/revoke-token.png)