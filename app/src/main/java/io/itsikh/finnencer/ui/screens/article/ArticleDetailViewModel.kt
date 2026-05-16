package io.itsikh.finnencer.ui.screens.article

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.itsikh.finnencer.data.ai.ArticleSummarizer
import io.itsikh.finnencer.data.dao.NewsDao
import io.itsikh.finnencer.data.entity.ArticleScore
import io.itsikh.finnencer.data.entity.ArticleSummary
import io.itsikh.finnencer.data.entity.NewsArticle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SummaryState {
    object Idle : SummaryState
    object Loading : SummaryState
    data class Ready(val text: String, val fromCache: Boolean) : SummaryState
    data class Failed(val message: String) : SummaryState
}

data class ArticleDetailState(
    val article: NewsArticle? = null,
    val scores: List<ArticleScore> = emptyList(),
    val summary: SummaryState = SummaryState.Idle,
)

@HiltViewModel
class ArticleDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val newsDao: NewsDao,
    private val summarizer: ArticleSummarizer,
) : ViewModel() {

    private val articleId: String = savedState.get<String>("articleId")
        ?: error("article detail opened without articleId")

    private val _state = MutableStateFlow(ArticleDetailState())
    val state: StateFlow<ArticleDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val article = newsDao.getArticle(articleId)
            val scores = newsDao.scoresFor(articleId)
            val cached: ArticleSummary? = newsDao.summaryFor(articleId)
            _state.value = ArticleDetailState(
                article = article,
                scores = scores,
                summary = cached?.let { SummaryState.Ready(it.summary, fromCache = true) }
                    ?: SummaryState.Idle,
            )
        }
    }

    fun requestSummary() {
        val article = _state.value.article ?: return
        if (_state.value.summary is SummaryState.Loading) return
        _state.value = _state.value.copy(summary = SummaryState.Loading)
        viewModelScope.launch {
            runCatching { summarizer.summarize(article) }
                .onSuccess { text ->
                    _state.value = _state.value.copy(
                        summary = SummaryState.Ready(text, fromCache = false)
                    )
                }
                .onFailure { t ->
                    _state.value = _state.value.copy(
                        summary = SummaryState.Failed(t.message ?: "Summary failed")
                    )
                }
        }
    }
}
