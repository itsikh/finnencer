package io.itsikh.finnencer.ui.screens.article

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.itsikh.finnencer.data.ai.ArticleSummarizer
import io.itsikh.finnencer.data.dao.NewsDao
import io.itsikh.finnencer.data.entity.ArticleScore
import io.itsikh.finnencer.data.entity.NewsArticle
import io.itsikh.finnencer.data.entity.SummaryVersion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
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
    val regenerateOpen: Boolean = false,
    val regenerating: Boolean = false,
    val regenerateError: String? = null,
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

    /** Reactive history of all summaries for this article, latest first. */
    val versions: StateFlow<List<SummaryVersion>> = newsDao
        .observeSummaryVersions(articleId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val article = newsDao.getArticle(articleId)
            val scores = newsDao.scoresFor(articleId)
            val latest = newsDao.latestSummaryVersion(articleId)?.summary
                ?: newsDao.summaryFor(articleId)?.summary
            _state.value = ArticleDetailState(
                article = article,
                scores = scores,
                summary = if (latest != null) SummaryState.Ready(latest, fromCache = true)
                else SummaryState.Idle,
            )
        }
    }

    /** One-tap default summary (used by the existing AI Summary card). */
    fun requestSummary() {
        val article = _state.value.article ?: return
        if (_state.value.summary is SummaryState.Loading) return
        _state.value = _state.value.copy(summary = SummaryState.Loading)
        viewModelScope.launch {
            runCatching { summarizer.summarizeIfMissing(article) }
                .onSuccess { text ->
                    _state.value = _state.value.copy(
                        summary = SummaryState.Ready(text, fromCache = false),
                    )
                }
                .onFailure { t ->
                    _state.value = _state.value.copy(
                        summary = SummaryState.Failed(t.message ?: "Summary failed"),
                    )
                }
        }
    }

    fun openRegenerate() { _state.value = _state.value.copy(regenerateOpen = true, regenerateError = null) }
    fun closeRegenerate() { _state.value = _state.value.copy(regenerateOpen = false) }

    fun regenerate(pagesTarget: Int?, customPrompt: String?) {
        val article = _state.value.article ?: return
        if (_state.value.regenerating) return
        _state.value = _state.value.copy(regenerating = true, regenerateError = null)
        viewModelScope.launch {
            runCatching { summarizer.regenerate(article, pagesTarget, customPrompt) }
                .onSuccess { text ->
                    _state.value = _state.value.copy(
                        regenerating = false,
                        regenerateOpen = false,
                        summary = SummaryState.Ready(text, fromCache = false),
                    )
                }
                .onFailure { t ->
                    _state.value = _state.value.copy(
                        regenerating = false,
                        regenerateError = t.message ?: "Regenerate failed",
                    )
                }
        }
    }

    /** Switch the displayed summary back to a previous version. */
    fun showVersion(v: SummaryVersion) {
        _state.value = _state.value.copy(summary = SummaryState.Ready(v.summary, fromCache = true))
    }
}
