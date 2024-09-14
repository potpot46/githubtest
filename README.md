# githubtest
subjects=["math", "science", "english", "filipino"]
print(len(subjects))
print(subjects)
for subj in subjects:
    print(subj)
print(subjects[2])
print("----------------------------")


subjects.append("computer")
print(subjects)
subjects.insert(3, "music")
print(subjects)
print('---------------------------')

removed=subjects.pop()
print(removed)
print(subjects)
del(subjects[1])
print(subjects)
subjects.remove("filipino")
print(subjects)

print("------------------------------")

subjects.sort()

print(subjects)
subjects.clear()
print(subjects)