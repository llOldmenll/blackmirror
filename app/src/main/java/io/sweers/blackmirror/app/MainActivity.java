/*
 * Copyright (c) 2018. Zac Sweers
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.sweers.blackmirror.app;

import android.os.Bundle;
import android.os.Looper;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.text.PrecomputedTextCompat;
import android.support.v4.widget.TextViewCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.AppCompatTextView;
import android.support.v7.widget.DividerItemDecoration;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import com.uber.autodispose.AutoDispose;
import com.uber.autodispose.android.lifecycle.AndroidLifecycleScopeProvider;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.FlowableEmitter;
import io.reactivex.FlowableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Cancellable;
import io.reactivex.functions.Predicate;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subscribers.DisposableSubscriber;
import io.sweers.blackmirror.spy.Spies;
import io.sweers.blackmirror.spy.Spy;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import timber.log.Timber;

public class MainActivity extends AppCompatActivity {

  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);

    final RecyclerView recyclerView = findViewById(R.id.list);
    LinearLayoutManager layoutManager = new LinearLayoutManager(this);
    layoutManager.setStackFromEnd(true);
    recyclerView.setLayoutManager(layoutManager);
    final ClassLoaderDisplayAdapter adapter = new ClassLoaderDisplayAdapter();
    recyclerView.setAdapter(adapter);

    DividerItemDecoration decoration =
        new DividerItemDecoration(this, DividerItemDecoration.VERTICAL);
    recyclerView.addItemDecoration(decoration);

    Flowable<String> logcatDump = Flowable.create(new FlowableOnSubscribe<String>() {
      @Override public void subscribe(FlowableEmitter<String> emitter) {
        try {
          // Clear first
          new ProcessBuilder().command("logcat", "-c").redirectErrorStream(true).start().waitFor();
          final Process process = new ProcessBuilder().command("logcat", "-d", "-v", "time")
              .redirectErrorStream(true)
              .start();
          emitter.setCancellable(new Cancellable() {
            @Override public void cancel() {
              process.destroy();
            }
          });
          BufferedReader bufferedReader =
              new BufferedReader(new InputStreamReader(process.getInputStream()));

          String line;
          while ((line = bufferedReader.readLine()) != null) {
            if (emitter.isCancelled()) {
              break;
            } else {
              emitter.onNext(line);
            }
          }
        } catch (Exception e) {
          emitter.onError(e);
        }
      }
    }, BackpressureStrategy.DROP).distinctUntilChanged().filter(new Predicate<String>() {
      @Override public boolean test(String s) {
        return s.contains("D/BlackMirror");
      }
    });

    Flowable<String> timberLogs = Flowable.create(new FlowableOnSubscribe<String>() {
      @Override public void subscribe(final FlowableEmitter<String> emitter) {
        final Timber.Tree tree = new Timber.Tree() {
          @Override protected void log(int priority, @Nullable String tag, @NotNull String message,
              @Nullable Throwable t) {
            if ("BlackMirror".equals(tag)) {
              emitter.onNext(message);
            }
          }
        };
        Timber.plant(tree);
        emitter.setCancellable(new Cancellable() {
          @Override public void cancel() {
            Timber.uproot(tree);
          }
        });
      }
    }, BackpressureStrategy.DROP);

    Flowable.concat(logcatDump, timberLogs)
        .subscribeOn(Schedulers.computation())
        .observeOn(AndroidSchedulers.from(Looper.getMainLooper(), true))
        .as(AutoDispose.<String>autoDisposable(AndroidLifecycleScopeProvider.from(this)))
        .subscribe(new DisposableSubscriber<String>() {
          @Override public void onNext(String newLog) {
            adapter.append(newLog);
            recyclerView.scrollToPosition(adapter.getItemCount() - 1);
          }

          @Override public void onError(Throwable t) {
            // Noop
          }

          @Override public void onComplete() {

          }
        });

    FloatingActionButton fab = findViewById(R.id.fab);
    fab.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        // By the time we've gotten here, there's no _new_ class loading to show. But we can
        // Kick some off
        try {
          Timber.d("Clicked");
          Spy spy = new Spy(MainActivity.this);
          String hello = spy.sayHello();

          String buildConfig = Spies.guessBuildConfig(MainActivity.this, "io.sweers.blackmirror.neighbor");
          Map<String, String> vals = Spies.buildConfigFields(MainActivity.this, "io.sweers.blackmirror.neighbor", buildConfig);

          Toast.makeText(MainActivity.this, hello, Toast.LENGTH_SHORT).show();
          Class.forName("io.reactivex.Single")
              .getDeclaredMethod("just", Object.class)
              .invoke(null, 5);
          //Single.just(5).subscribe();
        } catch (Exception e) {
          Timber.e(e);
        }
      }
    });
  }

  static class ClassLoaderDisplayAdapter extends RecyclerView.Adapter<ItemView> {

    private final List<String> logs = new ArrayList<>();

    public ClassLoaderDisplayAdapter() {
      setHasStableIds(true);
    }

    @Override public int getItemViewType(int position) {
      return 0;
    }

    @Override public long getItemId(int position) {
      return logs.get(position).hashCode();
    }

    @Override public ItemView onCreateViewHolder(ViewGroup viewGroup, int i) {
      return new ItemView((AppCompatTextView) LayoutInflater.from(viewGroup.getContext())
          .inflate(R.layout.item_view, viewGroup, false));
    }

    @Override public void onBindViewHolder(ItemView itemView, int i) {
      String log = logs.get(i);
      // Pass text computation future to AppCompatTextView,
      // which awaits result before measuring.
      itemView.textView.setTextFuture(PrecomputedTextCompat.getTextFuture(log,
          TextViewCompat.getTextMetricsParams(itemView.textView),
          /*optional custom executor*/
          null));
    }

    @Override public int getItemCount() {
      return logs.size();
    }

    void append(String newLog) {
      logs.add(newLog);
      notifyItemInserted(logs.size() - 1);
    }
  }

  static class ItemView extends RecyclerView.ViewHolder {

    private final AppCompatTextView textView;

    ItemView(AppCompatTextView textView) {
      super(textView);
      this.textView = textView;
      textView.setTextSize(12f);
    }
  }
}
