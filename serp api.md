# SerpAPI `google_ai_mode` response sample (Levi jeans query)

Reference doc — Epic 9 Story 9.1. Wrap in a fenced block so markdown
renderers do not consume `_` as italic markers (`quick_results`,
`Levi_Strauss_&_Co.`, etc. in the JSON below).

```json
curl --get https://serpapi.com/search \
 -d engine="google_ai_mode" \
 -d q="Levi+jeans" \
 -d api_key="API_KEY"

{
...
"quick_results": [
{
"title": "Levi's® Labor Day Sale | 30% off Sitewide",
"link": "https://www.levi.com/US/en_US/",
"snippet": "The Levi's® Labor Day Sale for 30% off sitewide, shop and save on your favorite men's and women's styles.",
"source": "Levi's",
"displayed_link": "https://www.levi.com",
"favicon": "https://serpapi.com/searches/68af016fdbbb466318f53099/images/12f2763e0224368fa4dd78d6c4ebfd36771e1c0dca1ebb40.png"
},
{
"title": "Thrift and Vintage Levi's Jeans",
"link": "https://www.secondhand.levi.com/shop/jeans",
"snippet": "Shop second hand and vintage Levis Jeans and Trucker Jackets at your favorite online thrift store, including used 501, 505, and 550s.",
"source": "Levi's SecondHand",
"displayed_link": "https://www.secondhand.levi.com",
"favicon": "https://serpapi.com/searches/68af016fdbbb466318f53099/images/12f2763e0224368fcd2ea097cd333bdabcdb3d2e09713da5.png"
}
],
"shopping_results": [
{
"title": "Levi's 501 Original Fit Men's Jeans",
"product_link": "https://www.google.com/search?prds=pvt:hg,pvo:29,mid:576462833771069843,imageDocid:927366645294926544,gpcid:790074975598790836,headlineOfferDocid:13000906125912082379,catalogid:18296818796508524238,productDocid:12390253486726265040&ibp=oshop&q=product&sa=X&ved=2ahUKEwi34OCBhquPAxVOkokEHXbcPLMQ8ccPegQIABAb",
"immersive_product_page_token": "eyJlaSI6bnVsbCwicHJvZHVjdGlkIjpudWxsLCJjYXRhbG9naWQiOiI3NTgxMDU0MTk2MjMwNzg0NTYwIiwiaGVhZGxpbmVPZmZlckRvY2lkIjoiMTI0NDM4Mzc3Mjk1NjMwMzM3OTgiLCJpbWFnZURvY2lkIjoiNDc0NDMzNzkxMTg3NTk5Mjk4MSIsInJkcyI6IlBDXzEyODU4NTU3ODIxOTUwNDUyODk2fFBST0RfUENfMTI4NTg1NTc4MjE5NTA0NTI4OTYiLCJxdWVyeSI6IkxldmkramVhbnMiLCJncGNpZCI6IjEyODU4NTU3ODIxOTUwNDUyODk2IiwibWlkIjoiNTc2NDYyOTAwMTU1OTM2OTQ0IiwicHZ0IjoiYSIsInV1bGUiOm51bGwsImdsIjpudWxsLCJobCI6bnVsbH0=",
"serpapi_immersive_product_api": "https://serpapi.com/search.json?engine=google_immersive_product&page_token=eyJlaSI6bnVsbCwicHJvZHVjdGlkIjpudWxsLCJjYXRhbG9naWQiOiI3NTgxMDU0MTk2MjMwNzg0NTYwIiwiaGVhZGxpbmVPZmZlckRvY2lkIjoiMTI0NDM4Mzc3Mjk1NjMwMzM3OTgiLCJpbWFnZURvY2lkIjoiNDc0NDMzNzkxMTg3NTk5Mjk4MSIsInJkcyI6IlBDXzEyODU4NTU3ODIxOTUwNDUyODk2fFBST0RfUENfMTI4NTg1NTc4MjE5NTA0NTI4OTYiLCJxdWVyeSI6IkxldmkramVhbnMiLCJncGNpZCI6IjEyODU4NTU3ODIxOTUwNDUyODk2IiwibWlkIjoiNTc2NDYyOTAwMTU1OTM2OTQ0IiwicHZ0IjoiYSIsInV1bGUiOm51bGwsImdsIjpudWxsLCJobCI6bnVsbH0%3D",
"thumbnail": "https://encrypted-tbn0.gstatic.com/shopping?q=tbn:ANd9GcS1jwaThn3miDmxfqwWR7rwdAAE1G62Jzg-7HvmvGsNtr-hA9yOoiReT4Vaxg&usqp=CAE",
"price": "$55.65",
"extracted_price": 55.65,
"old_price": "$79.50",
"extracted_old_price": 79.5,
"index": 0
},
{
"title": "Levi's Men's 511 Slim Fit Jeans",
"product_link": "https://www.google.com/search?prds=pvt:hg,pvo:29,mid:576462840585744507,imageDocid:7988756026854172948,gpcid:10868389383399353513,headlineOfferDocid:12666354779790272731,catalogid:15999505846080662005,productDocid:17276868584970186354,rds:PC_15454182066623491704%7CPROD_PC_15454182066623491704&ibp=oshop&q=product&sa=X&ved=2ahUKEwi34OCBhquPAxVOkokEHXbcPLMQ8ccPegQIABAg",
"immersive_product_page_token": "eyJjYXRhbG9naWQiOiIxNTk5OTUwNTg0NjA4MDY2MjAwNSIsImhlYWRsaW5lT2ZmZXJEb2NpZCI6IjEyNjY2MzU0Nzc5NzkwMjcyNzMxIiwiaW1hZ2VEb2NpZCI6Ijc5ODg3NTYwMjY4NTQxNzI5NDgiLCJyZHMiOiJQQ18xNTQ1NDE4MjA2NjYyMzQ5MTcwNHxQUk9EX1BDXzE1NDU0MTgyMDY2NjIzNDkxNzA0IiwicXVlcnkiOiJwcm9kdWN0IiwiZ3BjaWQiOiIxMDg2ODM4OTM4MzM5OTM1MzUxMyIsIm1pZCI6IjU3NjQ2Mjg0MDU4NTc0NDUwNyIsInB2dCI6ImhnIiwiZ2wiOiJjYSIsImhsIjoiZW4ifQ==",
"serpapi_immersive_product_api": "https://serpapi.com/search.json?engine=google_immersive_product&page_token=eyJjYXRhbG9naWQiOiIxNTk5OTUwNTg0NjA4MDY2MjAwNSIsImhlYWRsaW5lT2ZmZXJEb2NpZCI6IjEyNjY2MzU0Nzc5NzkwMjcyNzMxIiwiaW1hZ2VEb2NpZCI6Ijc5ODg3NTYwMjY4NTQxNzI5NDgiLCJyZHMiOiJQQ18xNTQ1NDE4MjA2NjYyMzQ5MTcwNHxQUk9EX1BDXzE1NDU0MTgyMDY2NjIzNDkxNzA0IiwicXVlcnkiOiJwcm9kdWN0IiwiZ3BjaWQiOiIxMDg2ODM4OTM4MzM5OTM1MzUxMyIsIm1pZCI6IjU3NjQ2Mjg0MDU4NTc0NDUwNyIsInB2dCI6ImhnIiwiZ2wiOiJjYSIsImhsIjoiZW4ifQ%3D%3D",
"thumbnail": "https://encrypted-tbn0.gstatic.com/shopping?q=tbn:ANd9GcSCvRpUgQDj4jDyqaSHiY-XiIkF7tbHwDvEVBZi-5dJ-cqvmvqQyQa_iRWjoJ8&usqp=CAE",
"price": "$39.99",
"extracted_price": 39.99,
"old_price": "$69.50",
"extracted_old_price": 69.5,
"rating": 4.3,
"reviews": 7000,
"index": 1
},
{
"title": "Levi's Men's 512 Slim Taper Eco Performance Jeans",
"product_link": "https://www.google.com/search?prds=pvt:hg,pvo:29,mid:576462816344651886,imageDocid:8296424061904123755,gpcid:7235414136344651487,headlineOfferDocid:2837926054404031388,catalogid:16492823197473936458,productDocid:12544303799310466688,rds:PC_7235414136344651487%7CPROD_PC_7235414136344651487&ibp=oshop&q=product&sa=X&ved=2ahUKEwi34OCBhquPAxVOkokEHXbcPLMQ8ccPegQIABAm",
"immersive_product_page_token": "eyJjYXRhbG9naWQiOiIxNjQ5MjgyMzE5NzQ3MzkzNjQ1OCIsImhlYWRsaW5lT2ZmZXJEb2NpZCI6IjI4Mzc5MjYwNTQ0MDQwMzEzODgiLCJpbWFnZURvY2lkIjoiODI5NjQyNDA2MTkwNDEyMzc1NSIsInJkcyI6IlBDXzcyMzU0MTQxMzYzNDQ2NTE0ODd8UFJPRF9QQ183MjM1NDE0MTM2MzQ0NjUxNDg3IiwicXVlcnkiOiJwcm9kdWN0IiwiZ3BjaWQiOiI3MjM1NDE0MTM2MzQ0NjUxNDg3IiwibWlkIjoiNTc2NDYyODE2MzQ0NjUxODg2IiwicHZ0IjoiaGciLCJnbCI6ImNhIiwiaGwiOiJlbiJ9",
"serpapi_immersive_product_api": "https://serpapi.com/search.json?engine=google_immersive_product&page_token=eyJjYXRhbG9naWQiOiIxNjQ5MjgyMzE5NzQ3MzkzNjQ1OCIsImhlYWRsaW5lT2ZmZXJEb2NpZCI6IjI4Mzc5MjYwNTQ0MDQwMzEzODgiLCJpbWFnZURvY2lkIjoiODI5NjQyNDA2MTkwNDEyMzc1NSIsInJkcyI6IlBDXzcyMzU0MTQxMzYzNDQ2NTE0ODd8UFJPRF9QQ183MjM1NDE0MTM2MzQ0NjUxNDg3IiwicXVlcnkiOiJwcm9kdWN0IiwiZ3BjaWQiOiI3MjM1NDE0MTM2MzQ0NjUxNDg3IiwibWlkIjoiNTc2NDYyODE2MzQ0NjUxODg2IiwicHZ0IjoiaGciLCJnbCI6ImNhIiwiaGwiOiJlbiJ9",
"thumbnail": "https://encrypted-tbn3.gstatic.com/shopping?q=tbn:ANd9GcSRRsmD-CLglGnMfLeKo34uxXlSJl-O0wIcYG_L1TfXh08MZYARD4DKk68HqQ&usqp=CAE",
"price": "$48.65",
"extracted_price": 48.65,
"old_price": "$69.50",
"extracted_old_price": 69.5,
"rating": 3.9,
"reviews": 74,
"index": 2
},
...
],
"text_blocks": [
{
"type": "paragraph",
"snippet": "Levi's is an iconic American denim brand known for its classic jeans, jackets, and casual wear. The company, Levi Strauss & Co., was founded in 1853 in San Francisco and received a patent for copper-riveted pants in 1873, creating the world's first modern denim jeans. The flagship product, the 501® Original, remains a timeless staple.",
"reference_indexes": [
4,
0,
1,
2
]
},
...
{
"type": "paragraph",
"snippet": "Men's",
"snippet_highlighted_words": [
"Men's"
]
},
{
"type": "list",
"list": [
{
"snippet": "501® Original: The classic straight-leg, button-fly jean that is the blueprint for all modern jeans.",
"snippet_links": [
{
"text": "501® Original",
"link": "https://www.google.com/search?prds=pvt:hg,pvo:29,mid:576462833771069843,imageDocid:927366645294926544,gpcid:790074975598790836,headlineOfferDocid:13000906125912082379,catalogid:18296818796508524238,productDocid:12390253486726265040&ibp=oshop&q=product&sa=X&ved=2ahUKEwi34OCBhquPAxVOkokEHXbcPLMQxa4PegQIABAS",
"shopping_results_reference_index": 0
}
]
},
{
"snippet": "511™ Slim: A slim fit through the hip and thigh with a tapered leg opening.",
"snippet_links": [
{
"text": "511™ Slim",
"link": "https://www.google.com/search?prds=pvt:hg,pvo:29,mid:576462840585744507,imageDocid:7988756026854172948,gpcid:10868389383399353513,headlineOfferDocid:12666354779790272731,catalogid:15999505846080662005,productDocid:17276868584970186354,rds:PC_15454182066623491704%7CPROD_PC_15454182066623491704&ibp=oshop&q=product&sa=X&ved=2ahUKEwi34OCBhquPAxVOkokEHXbcPLMQxa4PegQIABAU",
"shopping_results_reference_index": 1
}
]
},
{
"snippet": "512™ Slim Taper: Similar to the 511, but with a more pronounced taper to the ankle.",
"snippet_links": [
{
"text": "512™ Slim Taper",
"link": "https://www.google.com/search?prds=pvt:hg,pvo:29,mid:576462816344651886,imageDocid:8296424061904123755,gpcid:7235414136344651487,headlineOfferDocid:2837926054404031388,catalogid:16492823197473936458,productDocid:12544303799310466688,rds:PC_7235414136344651487%7CPROD_PC_7235414136344651487&ibp=oshop&q=product&sa=X&ved=2ahUKEwi34OCBhquPAxVOkokEHXbcPLMQxa4PegQIABAW",
"shopping_results_reference_index": 2
}
]
},
...
],
"reference_indexes": [
1,
6
]
},
...
],
"reconstructed_markdown": "Levi's is an iconic American denim brand known for its classic jeans, jackets, and casual wear. The company, Levi Strauss & Co., was founded in 1853 in San Francisco and received a patent for copper-riveted pants in 1873, creating the world's first modern denim jeans. The flagship product, the 501® Original, remains a timeless staple.\n\n- **501® Original**: The classic straight-leg, button-fly jean that is the blueprint for all modern jeans.\n- **511™ Slim**: A slim fit through the hip and thigh with a tapered leg opening.\n- **512™ Slim Taper**: Similar to the 511, but with a more pronounced taper to the ankle.",
"references": [
{
"title": "Levi’s Outlet Store",
"link": "https://www.google.com/viewer/place?mid=/g/1vr1wxdw",
"snippet": "Denim pioneer dating back to 1873 with a selection of jeans, casualwear, jackets & accessories.",
"source": "Google",
"index": 0
},
{
"title": "501® Original Fit Men's Jeans - Dark Wash | Levi's® US",
"link": "https://www.levi.com/US/en_US/clothing/men/jeans/straight/501-original-fit-mens-jeans/p/005013408",
"snippet": "Close your eyes. Think “jeans.” Now open. They were 501® Originals, right? With a classic straight leg and iconic styling, they're literally the blueprint for e...",
"source": "Levi's",
"index": 1
},
{
"title": "Levi Strauss & Co. - Wikipedia",
"link": "https://en.wikipedia.org/wiki/Levi_Strauss_%26_Co.",
"snippet": "History — Origin and formation (1853–1890s) The original Levi Strauss logo, 1892. German-Jewish immigrant Levi Strauss was born on February 26, 1829. He grew up...",
"source": "Wikipedia",
"index": 2
},
...
],
...
}
```
