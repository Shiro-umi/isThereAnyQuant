        LaunchedEffect(isLoading) {
            if (!isLoading || symbolList.isEmpty()) return@LaunchedEffect

            val client = createHttpClient()

            try {
                client.post("/symbol/submit") {
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        put("candles", json.encodeToString(list))
                        put("symbols", json.encodeToString(symbolList))
                    })
                }
                // 提交完成后不清空 symbolList，保持已标记的状态
                selectedCandle = null
                lastSelectedCandle = null

                // 请求刷新数据以更新状态
                refreshTrigger++
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                client.close()
                isLoading = false
            }
        }

        // 使用当前的 symbolList 而不是备份列表
        SymbolCanvas(
            modifier = Modifier.fillMaxSize(),
            data = list,
            symbols = symbolList
        ) { candle, offset ->
            selectedCandle = candle
            popupOffset = offset
        }
