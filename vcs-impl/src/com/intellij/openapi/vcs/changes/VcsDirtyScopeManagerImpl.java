package com.intellij.openapi.vcs.changes;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.project.DumbAwareRunnable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsRoot;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;

/**
 * @author max
 */
public class VcsDirtyScopeManagerImpl extends VcsDirtyScopeManager implements ProjectComponent {
  private final Project myProject;
  private final ChangeListManager myChangeListManager;
  private final ProjectLevelVcsManager myVcsManager;

  private final DirtBuilder myDirtBuilder;
  private final VcsGuess myGuess;
  private final SynchronizedLife myLife;

  public VcsDirtyScopeManagerImpl(Project project, ChangeListManager changeListManager, ProjectLevelVcsManager vcsManager) {
    myProject = project;
    myChangeListManager = changeListManager;
    myVcsManager = vcsManager;

    myLife = new SynchronizedLife();
    myGuess = new VcsGuess(myProject);
    myDirtBuilder = new DirtBuilder(myGuess);
  }

  public void projectOpened() {
    if (ApplicationManager.getApplication().isUnitTestMode()) {
      myLife.born();
      final AbstractVcs[] vcss = myVcsManager.getAllActiveVcss();
      if (vcss.length > 0) {
        markEverythingDirty();
      }
    }
    else {
      StartupManager.getInstance(myProject).registerPostStartupActivity(new DumbAwareRunnable() {
        public void run() {
          myLife.born();
          markEverythingDirty();
        }
      });
    }
  }

  public void suspendMe() {
    myLife.suspendMe();
  }

  public void reanimate() {
    final Ref<Boolean> wasNotEmptyRef = new Ref<Boolean>();
    myLife.releaseMe(new Runnable() {
      public void run() {
        wasNotEmptyRef.set(! myDirtBuilder.isEmpty());
      }
    });
    if (Boolean.TRUE.equals(wasNotEmptyRef.get())) {
      myChangeListManager.scheduleUpdate();
    }
  }

  public void markEverythingDirty() {
    if (myProject.isDisposed()) return;

    final LifeDrop lifeDrop = myLife.doIfAlive(new Runnable() {
      public void run() {
        myDirtBuilder.everythingDirty();
      }
    });

    if (lifeDrop.isDone() && (! lifeDrop.isSuspened())) {
      myChangeListManager.scheduleUpdate();
    }
  }

  public void projectClosed() {
    killSelf();
  }

  @NotNull @NonNls
  public String getComponentName() {
    return "VcsDirtyScopeManager";
  }

  public void initComponent() {}

  private void killSelf() {
    myLife.kill(new Runnable() {
      public void run() {
        myDirtBuilder.reset();
      }
    });
  }

  public void disposeComponent() {
    killSelf();
  }

  private void convertPaths(@Nullable final Collection<FilePath> from, final Collection<FilePathUnderVcs> to) {
    if (from != null) {
      for (FilePath fp : from) {
        final AbstractVcs vcs = myGuess.getVcsForDirty(fp);
        if (vcs != null) {
          to.add(new FilePathUnderVcs(fp, vcs));
        }
      }
    }
  }

  public boolean filePathsDirty(@Nullable final Collection<FilePath> filesDirty, @Nullable final Collection<FilePath> dirsRecursivelyDirty) {
    final ArrayList<FilePathUnderVcs> filesConverted = filesDirty == null ? null : new ArrayList<FilePathUnderVcs>(filesDirty.size());
    final ArrayList<FilePathUnderVcs> dirsConverted = dirsRecursivelyDirty == null ? null : new ArrayList<FilePathUnderVcs>(dirsRecursivelyDirty.size());

    ApplicationManager.getApplication().runReadAction(new Runnable() {
      public void run() {
        convertPaths(filesDirty, filesConverted);
        convertPaths(dirsRecursivelyDirty, dirsConverted);
      }
    });
    final boolean haveStuff = ((filesConverted != null) && (! filesConverted.isEmpty())) ||
                              ((dirsConverted != null) && (! dirsConverted.isEmpty()));
    if (! haveStuff) return false;

    return takeDirt(new Consumer<DirtBuilder>() {
      public void consume(final DirtBuilder dirt) {
        if (filesConverted != null) {
          for (FilePathUnderVcs root : filesConverted) {
            dirt.addDirtyFile(root);
          }
        }
        if (dirsConverted != null) {
          for (FilePathUnderVcs root : dirsConverted) {
            dirt.addDirtyDirRecursively(root);
          }
        }
      }
    });
  }

  private boolean takeDirt(final Consumer<DirtBuilder> filler) {
    final Ref<Boolean> wasNotEmptyRef = new Ref<Boolean>();
    final Runnable runnable = new Runnable() {
      public void run() {
        filler.consume(myDirtBuilder);
        wasNotEmptyRef.set(!myDirtBuilder.isEmpty());
      }
    };
    final LifeDrop lifeDrop = myLife.doIfAlive(runnable);

    if (lifeDrop.isDone() && (! lifeDrop.isSuspened()) && (Boolean.TRUE.equals(wasNotEmptyRef.get()))) {
      myChangeListManager.scheduleUpdate();
    }
    // no sense in checking correct here any more: vcs is searched for asynchronously
    return (! lifeDrop.isDone());
  }

  private void convert(@Nullable final Collection<VirtualFile> from, final Collection<VcsRoot> to) {
    if (from != null) {
      for (VirtualFile vf : from) {
        final AbstractVcs vcs = myGuess.getVcsForDirty(vf);
        if (vcs != null) {
          to.add(new VcsRoot(vcs, vf));
        }
      }
    }
  }

  public boolean filesDirty(@Nullable final Collection<VirtualFile> filesDirty, @Nullable final Collection<VirtualFile> dirsRecursivelyDirty) {
    final ArrayList<VcsRoot> filesConverted = filesDirty == null ? null : new ArrayList<VcsRoot>(filesDirty.size());
    final ArrayList<VcsRoot> dirsConverted = dirsRecursivelyDirty == null ? null : new ArrayList<VcsRoot>(dirsRecursivelyDirty.size());

    ApplicationManager.getApplication().runReadAction(new Runnable() {
      public void run() {
        convert(filesDirty, filesConverted);
        convert(dirsRecursivelyDirty, dirsConverted);
      }
    });
    final boolean haveStuff = ((filesConverted != null) && (! filesConverted.isEmpty())) ||
                              ((dirsConverted != null) && (! dirsConverted.isEmpty()));
    if (! haveStuff) return false;

    return takeDirt(new Consumer<DirtBuilder>() {
      public void consume(final DirtBuilder dirt) {
        if (filesConverted != null) {
          for (VcsRoot root : filesConverted) {
            dirt.addDirtyFile(root);
          }
        }
        if (dirsConverted != null) {
          for (VcsRoot root : dirsConverted) {
            dirt.addDirtyDirRecursively(root);
          }
        }
      }
    });
  }

  public void fileDirty(final VirtualFile file) {
    final AbstractVcs vcs = myGuess.getVcsForDirty(file);
    if (vcs == null) return;
    final VcsRoot root = new VcsRoot(vcs, file);
    takeDirt(new Consumer<DirtBuilder>() {
      public void consume(DirtBuilder dirtBuilder) {
        dirtBuilder.addDirtyFile(root);
      }
    });
  }

  public void fileDirty(final FilePath file) {
    final AbstractVcs vcs = myGuess.getVcsForDirty(file);
    if (vcs == null) return;
    final FilePathUnderVcs root = new FilePathUnderVcs(file, vcs);
    takeDirt(new Consumer<DirtBuilder>() {
      public void consume(DirtBuilder dirtBuilder) {
        dirtBuilder.addDirtyFile(root);
      }
    });
  }

  public void dirDirtyRecursively(final VirtualFile dir, final boolean scheduleUpdate) {
    dirDirtyRecursively(dir);
  }

  public void dirDirtyRecursively(final VirtualFile dir) {
    final AbstractVcs vcs = myGuess.getVcsForDirty(dir);
    if (vcs == null) return;
    final VcsRoot root = new VcsRoot(vcs, dir);
    takeDirt(new Consumer<DirtBuilder>() {
      public void consume(DirtBuilder dirtBuilder) {
        dirtBuilder.addDirtyDirRecursively(root);
      }
    });
  }

  public void dirDirtyRecursively(final FilePath path) {
    final AbstractVcs vcs = myGuess.getVcsForDirty(path);
    if (vcs == null) return;
    final FilePathUnderVcs root = new FilePathUnderVcs(path, vcs);
    takeDirt(new Consumer<DirtBuilder>() {
      public void consume(DirtBuilder dirtBuilder) {
        dirtBuilder.addDirtyDirRecursively(root);
      }
    });
  }

  @Nullable
  public VcsInvalidated retrieveScopes() {
    final Ref<DirtBuilder> dirtCopyRef = new Ref<DirtBuilder>();

    final LifeDrop lifeDrop = myLife.doIfAlive(new Runnable() {
      public void run() {
        dirtCopyRef.set(new DirtBuilder(myDirtBuilder));
        myDirtBuilder.reset();
      }
    });

    if (lifeDrop.isDone() && (! dirtCopyRef.isNull())) {
      return ApplicationManager.getApplication().runReadAction(new Computable<VcsInvalidated>() {
        public VcsInvalidated compute() {
          final Scopes scopes = new Scopes(myProject, myGuess);
          scopes.takeDirt(dirtCopyRef.get());
          return scopes.retrieveAndClear();
        }
      });
    }
    return null;
  }

  private String toStringScopes(final VcsInvalidated vcsInvalidated) {
    final StringBuilder sb = new StringBuilder();
    sb.append("is everything dirty: ").append(vcsInvalidated.isEverythingDirty()).append(";\n");
    for (VcsDirtyScope scope : vcsInvalidated.getScopes()) {
      sb.append("|\nFiles: ");
      for (FilePath path : scope.getDirtyFiles()) {
        sb.append(path).append('\n');
      }
      sb.append("\nDirs: ");
      for (FilePath filePath : scope.getRecursivelyDirtyDirectories()) {
        sb.append(filePath).append('\n');
      }
    }
    sb.append("-------------");
    return sb.toString();
  }

  private static class LifeDrop {
    private final boolean myDone;
    private final boolean mySuspened;

    private LifeDrop(boolean done, boolean suspened) {
      myDone = done;
      mySuspened = suspened;
    }

    public boolean isDone() {
      return myDone;
    }

    public boolean isSuspened() {
      return mySuspened;
    }
  }

  private static class SynchronizedLife {
    private LifeStages myStage;
    private final Object myLock;
    private boolean mySuspended;

    private SynchronizedLife() {
      myStage = LifeStages.NOT_BORN;
      myLock = new Object();
    }

    public void born() {
      synchronized (myLock) {
        myStage = LifeStages.ALIVE;
      }
    }

    public void kill(Runnable runnable) {
      synchronized (myLock) {
        myStage = LifeStages.DEAD;
        runnable.run();
      }
    }

    public void suspendMe() {
      synchronized (myLock) {
        if (LifeStages.ALIVE.equals(myStage)) {
          mySuspended = true;
        }
      }
    }

    public void releaseMe(final Runnable runnable) {
      synchronized (myLock) {
        if (LifeStages.ALIVE.equals(myStage)) {
          mySuspended = false;
          runnable.run();
        }
      }
    }

    public LifeDrop doIfAliveAndNotSuspended(final Runnable runnable) {
      synchronized (myLock) {
        synchronized (myLock) {
          if (LifeStages.ALIVE.equals(myStage) && (! mySuspended)) {
            runnable.run();
            return new LifeDrop(true, mySuspended);
          }
          return new LifeDrop(false, mySuspended);
        }
      }
    }

    // allow work under inner lock: inner class, not wide scope
    public LifeDrop doIfAlive(final Runnable runnable) {
      synchronized (myLock) {
        if (LifeStages.ALIVE.equals(myStage)) {
          runnable.run();
          return new LifeDrop(true, mySuspended);
        }
        return new LifeDrop(false, mySuspended);
      }
    }

    private static enum LifeStages {
      NOT_BORN,
      ALIVE,
      DEAD
    }
  }
}
